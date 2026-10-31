package com.gitory.backend.coverletter.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardLabel;
import com.gitory.backend.card.domain.CardLabelReader;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 자소서를 만들고 지우며, 단계마다 사용자가 바꾸는 것(카드·답·고친 초안)을 저장하고 다음 AI 단계를 줄 세운다
 * AI 를 새로 돌리는 만들기와 다시 시도만 호출 제한을 세고, 같은 요청 키로 다시 온 만들기는 세지 않는다
 */
@Service
@RequiredArgsConstructor
public class CoverLetterService {

    private static final int JOB_ROLE_MAX_LENGTH = 100;
    private static final int MEMO_MAX_LENGTH = 1_000;
    private static final int MAX_QUESTIONS = 10;
    private static final int QUESTION_MAX_LENGTH = 500;
    private static final int MIN_CHAR_LIMIT = 100;
    private static final int MAX_CHAR_LIMIT = 5_000;
    // 근거 없는 임시 값이라, 써 보며 바꿀 수 있다
    private static final int DEFAULT_CHAR_LIMIT = 700;
    private static final int KEY_MAX_LENGTH = 255;
    // AI 가 답 하나를 받는 상한과 같아, 넘는 답이 초안 쓰기 실패로 이어지지 않는다
    private static final int ANSWER_MAX_LENGTH = 1_000;
    private static final int DRAFT_MAX_LENGTH = 10_000;

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CardMaterialAnswerRepository rememberedAnswers;
    private final CardMaterialReader cardMaterials;
    private final CardLabelReader cardLabels;
    private final UserRateLimiter rateLimiter;
    private final AuditLog auditLog;
    private final CoverLetterViewFactory views;
    private final TransactionTemplate transaction;

    /** 같은 사용자·같은 요청 키면 보낸 내용과 상관없이 처음 만든 자소서를 돌려준다 */
    public CoverLetterView create(Long userId, String jobRole, String memo, List<NewQuestion> newQuestions,
                                  String idempotencyKey) {

        String key = checkedKey(idempotencyKey);
        checkInput(jobRole, memo, newQuestions);

        Optional<CoverLetter> existing = existing(userId, key);
        if (existing.isPresent()) {
            return views.of(existing.get());
        }

        if (!cardMaterials.hasConfirmed(userId)) {
            throw new NoConfirmedCardsException();
        }
        rateLimiter.acquire(RateLimitedAction.COVER_LETTER, userId, 1);

        return views.of(save(userId, jobRole, memo, newQuestions, key));

    }

    /** 카드 고르기가 끝난 뒤에만 바꾸며, 카드는 내 확정 카드여야 하고 다른 문항과 겹쳐도 된다 */
    @Transactional
    public CoverLetterView chooseCards(UUID letterId, UUID questionId, Long userId, UUID primaryCardId,
                                       UUID supportingCardId) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        CoverLetterQuestion question = questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);
        if (letter.getStage() != CoverLetterStage.ASSIGNED) {
            throw new CoverLetterStateException("카드는 카드 고르기가 끝난 뒤에만 바꿀 수 있습니다.");
        }
        if (primaryCardId != null && primaryCardId.equals(supportingCardId)) {
            throw new InvalidCoverLetterInputException("중심 카드와 보조 카드가 같습니다.");
        }

        question.chooseCards(confirmedCardId(userId, primaryCardId), confirmedCardId(userId, supportingCardId));
        letter.touch();

        return views.of(letter);

    }

    /** 카드 고르기가 끝난 뒤에만 부르며, 지금 배정으로 질문 만들기를 줄 세운 뒤로는 카드를 바꿀 수 없다 */
    @Transactional
    public CoverLetterView startAsking(UUID letterId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        if (letter.getStage() != CoverLetterStage.ASSIGNED) {
            throw new CoverLetterStateException("질문은 카드 고르기가 끝난 뒤에만 만들 수 있습니다.");
        }

        letter.startAsking();

        return views.of(letter);

    }

    /** 질문을 받은 뒤에만 부르며, 문항의 답을 보낸 것으로 통째로 바꾸고 보내지 않은 항목은 답이 없는 것으로 남긴다 */
    @Transactional
    public CoverLetterView answer(UUID letterId, UUID questionId, Long userId, List<NewAnswer> newAnswers) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        CoverLetterQuestion question = questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);
        if (letter.getStage() != CoverLetterStage.ASKED) {
            throw new CoverLetterStateException("답은 질문을 받은 뒤에만 저장할 수 있습니다.");
        }

        question.answer(checkedAnswers(question, newAnswers));
        letter.touch();

        return views.of(letter);

    }

    /**
     * 질문을 받은 뒤에만 부르며, 답하지 않은 질문은 넘긴 것으로 두고 초안 쓰기를 줄 세운다
     * 답은 이때 중심 카드에 기억해, 답하다 그만둔 자소서의 덜 쓴 답이 전에 써 둔 답을 덮지 않게 한다
     */
    @Transactional
    public CoverLetterView startDrafting(UUID letterId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        if (letter.getStage() != CoverLetterStage.ASKED) {
            throw new CoverLetterStateException("초안은 질문을 받은 뒤에만 쓸 수 있습니다.");
        }

        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        loaded.forEach(CoverLetterQuestion::skipUnanswered);
        remember(loaded);
        letter.startDrafting();

        return views.of(letter);

    }

    /** 초안을 받은 뒤에만 고치며, AI 원본은 그대로 두고 고친 글로 글자 수와 남은 빈칸을 다시 센다 */
    @Transactional
    public CoverLetterView editDraft(UUID letterId, UUID questionId, Long userId, String text) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        CoverLetterQuestion question = questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);
        if (letter.getStage() != CoverLetterStage.DRAFTED) {
            throw new CoverLetterStateException("초안은 다 쓴 뒤에만 고칠 수 있습니다.");
        }
        if (text == null) {
            throw new InvalidCoverLetterInputException("고친 글이 없습니다.");
        }
        requireLength(text, "고친 글 칸", DRAFT_MAX_LENGTH);

        question.edit(text);
        letter.touch();

        return views.of(letter);

    }

    /** 어느 단계에서든 지우며, 워커가 돌리던 단계의 결과는 자소서가 없어 저장되지 않는다 */
    @Transactional
    public void delete(UUID letterId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.DELETE_COVER_LETTER);
        letters.delete(letter);
        auditLog.ok(userId, AuditAction.DELETE_COVER_LETTER, letter.getId());

    }

    @Transactional
    public CoverLetterView retry(UUID letterId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId, AuditAction.EDIT_COVER_LETTER);
        if (letter.getStage() != CoverLetterStage.FAILED) {
            throw new CoverLetterStateException("실패한 자소서만 다시 시도할 수 있습니다.");
        }
        rateLimiter.acquire(RateLimitedAction.COVER_LETTER, userId, 1);

        letter.retry();

        return views.of(letter);

    }

    private CoverLetter save(Long userId, String jobRole, String memo, List<NewQuestion> newQuestions, String key) {

        try {
            return transaction.execute(status -> {
                CoverLetter letter = letters.save(CoverLetter.start(userId, jobRole, memo, key));
                for (int seq = 1; seq <= newQuestions.size(); seq++) {
                    NewQuestion question = newQuestions.get(seq - 1);
                    questions.save(CoverLetterQuestion.of(letter.getId(), seq, question.text(),
                            question.charLimit() == null ? DEFAULT_CHAR_LIMIT : question.charLimit()));
                }
                return letter;
            });
        } catch (DataIntegrityViolationException e) {
            return existing(userId, key).orElseThrow(() -> e);
        }

    }

    // 넘긴 항목은 전에 기억한 답을 지우지 않고, 같은 카드·항목을 여러 문항이 답했으면 뒤 문항의 답이 남는다
    private void remember(List<CoverLetterQuestion> loaded) {

        Set<Long> live = cardLabels.liveLabels(loaded.stream()
                .map(CoverLetterQuestion::getPrimaryCardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())).keySet();
        for (CoverLetterQuestion question : loaded) {
            if (question.getPrimaryCardId() == null || !live.contains(question.getPrimaryCardId())) {
                continue;
            }
            for (MaterialAnswer answer : question.getAnswers()) {
                if (!answer.skipped() && CardMaterialAnswer.remembers(answer.item())) {
                    rememberedAnswers.remember(question.getPrimaryCardId(), answer.item().name(), answer.text(),
                            CoverLetterTime.now());
                }
            }
        }

    }

    private Optional<CoverLetter> existing(Long userId, String key) {

        // 키 없이 찾으면 Spring Data 가 IS NULL 조건으로 바꿔 키 없이 만든 자소서들이 걸린다
        if (key == null) {
            return Optional.empty();
        }

        return letters.findByUserIdAndIdempotencyKey(userId, key);

    }

    private Long confirmedCardId(Long userId, UUID publicId) {

        if (publicId == null) {
            return null;
        }

        return cardLabels.confirmedOf(userId, publicId)
                .map(CardLabel::id)
                .orElseThrow(() -> new InvalidCoverLetterInputException("내 확정 카드만 고를 수 있습니다."));

    }

    private CoverLetter lockOwned(UUID publicId, Long userId, AuditAction action) {

        return letters.findWithLockByPublicIdAndUserId(publicId, userId).orElseThrow(() -> {
            letters.findByPublicId(publicId).ifPresent(others -> auditLog.denied(userId, action, others.getId()));
            return new CoverLetterNotFoundException();
        });

    }

    private static String checkedKey(String idempotencyKey) {

        // 빈 키를 키로 쓰면 빈 키로 온 요청이 모두 처음 만든 것 하나를 돌려받는다
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        // 아주 긴 키는 유니크 인덱스 한도에 걸려 저장 때 500 이 된다
        if (idempotencyKey.length() > KEY_MAX_LENGTH) {
            throw new InvalidCoverLetterInputException("Idempotency-Key 는 " + KEY_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }
        return idempotencyKey;

    }

    private static void checkInput(String jobRole, String memo, List<NewQuestion> newQuestions) {

        requireText(jobRole, "지원 직무 칸", JOB_ROLE_MAX_LENGTH);
        if (memo != null) {
            requireLength(memo, "메모 칸", MEMO_MAX_LENGTH);
        }
        if (newQuestions == null || newQuestions.isEmpty() || newQuestions.size() > MAX_QUESTIONS) {
            throw new InvalidCoverLetterInputException("문항은 1개부터 " + MAX_QUESTIONS + "개까지입니다.");
        }
        for (NewQuestion question : newQuestions) {
            if (question == null) {
                throw new InvalidCoverLetterInputException("빈 문항이 있습니다.");
            }
            requireText(question.text(), "문항 칸", QUESTION_MAX_LENGTH);
            Integer limit = question.charLimit();
            if (limit != null && (limit < MIN_CHAR_LIMIT || limit > MAX_CHAR_LIMIT)) {
                throw new InvalidCoverLetterInputException(
                        "글자 수는 " + MIN_CHAR_LIMIT + "부터 " + MAX_CHAR_LIMIT + "까지입니다.");
            }
        }

    }

    // 지난 답을 고치지 않고 다시 보내면 미리 채운 답으로 남겨, 화면이 '지난번에 답한 내용' 표시를 그대로 둘 수 있게 한다
    private static List<MaterialAnswer> checkedAnswers(CoverLetterQuestion question, List<NewAnswer> newAnswers) {

        if (newAnswers == null) {
            throw new InvalidCoverLetterInputException("답 목록이 없습니다.");
        }

        Set<MaterialItem> asked = question.getAsks().stream().map(MaterialAsk::item).collect(Collectors.toSet());
        Map<MaterialItem, MaterialAnswer> before = question.getAnswers().stream()
                .collect(Collectors.toMap(MaterialAnswer::item, Function.identity()));
        Set<MaterialItem> seen = EnumSet.noneOf(MaterialItem.class);
        List<MaterialAnswer> checked = new ArrayList<>();
        for (NewAnswer answer : newAnswers) {
            if (answer == null) {
                throw new InvalidCoverLetterInputException("빈 답이 있습니다.");
            }
            MaterialItem item = itemOf(answer.item());
            if (!asked.contains(item)) {
                throw new InvalidCoverLetterInputException("이 문항의 질문에 없는 항목입니다: " + item);
            }
            if (!seen.add(item)) {
                throw new InvalidCoverLetterInputException("같은 항목의 답이 두 번 있습니다: " + item);
            }
            if (answer.skipped()) {
                checked.add(new MaterialAnswer(item, null, true, false));
                continue;
            }
            requireText(answer.text(), "답 칸", ANSWER_MAX_LENGTH);
            MaterialAnswer previous = before.get(item);
            boolean reused = previous != null && previous.reused() && answer.text().equals(previous.text());
            checked.add(new MaterialAnswer(item, answer.text(), false, reused));
        }
        return checked;

    }

    private static MaterialItem itemOf(String item) {

        try {
            return MaterialItem.valueOf(item);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidCoverLetterInputException("답의 항목이 올바르지 않습니다.");
        }

    }

    private static void requireText(String text, String field, int maxLength) {

        if (text == null || text.isBlank()) {
            throw new InvalidCoverLetterInputException(field + "이 비었습니다.");
        }
        requireLength(text, field, maxLength);

    }

    // DB 가 U+0000 을 저장하지 못해, 그대로 두면 저장 때 500 이 된다
    private static void requireLength(String text, String field, int maxLength) {

        if (text.indexOf('\u0000') >= 0) {
            throw new InvalidCoverLetterInputException(field + "에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        if (text.length() > maxLength) {
            throw new InvalidCoverLetterInputException(field + "은 " + maxLength + "자를 넘을 수 없습니다.");
        }

    }
}
