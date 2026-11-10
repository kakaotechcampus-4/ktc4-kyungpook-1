package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterFill;
import com.gitory.backend.agent.port.CoverLetterFillRequest;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.agent.port.CoverLetterTurnPort;
import com.gitory.backend.agent.port.CoverLetterTurnReply;
import com.gitory.backend.agent.port.CoverLetterTurnRequest;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardLabelReader;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.coverletter.infra.CoverLetterTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 초안을 받은 자소서에서 AI 와 대화해 빠진 재료를 받고, 채울 수 있는 답이면 그 문항만 다시 써 지금 글을 바꾼다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 열고, 대화 한 번에 호출 제한을 한 번 센다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverLetterTurnService {

    private static final int TEXT_MAX_LENGTH = 500;

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CoverLetterTurnRepository turns;
    private final CardMaterialAnswerRepository rememberedAnswers;
    private final CardMaterialReader cardMaterials;
    private final CardLabelReader cardLabels;
    private final CoverLetterTurnPort turnPort;
    private final UserRateLimiter rateLimiter;
    private final AuditLog auditLog;
    private final CoverLetterViewFactory views;
    private final CoverLetterSuggestionService suggestions;
    private final PlatformTransactionManager transactionManager;

    /** 어느 단계에서든 읽으며, 대화가 없으면 빈 목록이다 */
    @Transactional(readOnly = true)
    public List<CoverLetterTurnView> turns(UUID letterId, Long userId) {

        CoverLetter letter = letters.findByPublicIdAndUserId(letterId, userId)
                .orElseThrow(() -> notFound(letterId, userId, AuditAction.VIEW_COVER_LETTER));
        Map<Integer, UUID> questionIds = questions.findByCoverLetterIdOrderBySeq(letter.getId()).stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), CoverLetterQuestion::getPublicId));

        return turnViews(letter.getId(), questionIds);

    }

    /** 중심 카드가 없는 문항을 처음 열면 쓸 만한 카드·레포 기록을 먼저 받아 저장하며, 받지 못해도 대화는 이어진다 */
    public CoverLetterTalk open(UUID letterId, UUID questionId, Long userId, String login) {

        suggestions.suggestIfNeeded(letterId, questionId, userId, login);

        return talk(letterId, questionId, userId, TurnTrigger.OPEN, null);

    }

    public CoverLetterTalk reply(UUID letterId, UUID questionId, Long userId, String text) {

        checkText(text);

        return talk(letterId, questionId, userId, TurnTrigger.USER_REPLY, text);

    }

    public CoverLetterTalk skip(UUID letterId, UUID questionId, Long userId) {

        return talk(letterId, questionId, userId, TurnTrigger.SKIP, null);

    }

    /**
     * AI 가 잠시 응답하지 못하면 사용자의 말도 저장하지 않고 다시 보낼 수 있는 실패로 끝내, 다시 보내도 같은 말이 두 번 남지 않는다
     * 다시 쓰기만 실패하면 대화와 답은 남기고 글은 그대로 둔 채 rewriteFailed 로 알린다
     */
    private CoverLetterTalk talk(UUID letterId, UUID questionId, Long userId, TurnTrigger trigger, String text) {

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        CoverLetterTurnRequest request = read.execute(status -> request(letterId, questionId, userId, trigger, text));

        rateLimiter.acquire(RateLimitedAction.COVER_LETTER_TURN, userId, 1);
        CoverLetterTurnReply reply = next(request);

        TransactionTemplate write = new TransactionTemplate(transactionManager);
        Saved saved = write.execute(status -> save(letterId, questionId, userId, text, reply));
        if (saved.fill() == null) {
            return saved.talk();
        }

        // 항목마다 앞에서 다시 쓴 글을 바탕으로 다음 빈칸을 채워야 해서, 한 항목씩 AI 를 부르고 저장한다
        CoverLetterFillRequest fill = saved.fill();
        for (int done = 1; ; done++) {
            CoverLetterFillRequest current = fill;
            int applied = done;
            Optional<CoverLetterFill> rewritten = rewrite(current);
            Step step = write.execute(status -> applyFill(letterId, questionId, userId, current, rewritten,
                    saved.items(), applied));
            if (step.talk() != null) {
                return step.talk();
            }
            fill = step.next();
        }

    }

    private Step applyFill(UUID letterId, UUID questionId, Long userId, CoverLetterFillRequest current,
                           Optional<CoverLetterFill> rewritten, List<MaterialItem> items, int done) {

        CoverLetter letter = lockOwned(letterId, userId);
        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        CoverLetterQuestion question = targetOf(loaded, questionId);
        // AI 가 다시 쓴 글을 되돌렸거나, 다시 쓰는 동안 사용자가 글을 고쳤으면 그 글을 덮지 않고 거기서 멈춘다
        boolean applied = rewritten.isPresent() && !rewritten.get().reverted()
                && current.target().currentText().equals(question.getDraftText());
        if (!applied) {
            return new Step(null, talkOf(letter, question, true));
        }
        question.refill(CoverLetterPolishService.draftOf(rewritten.get().draft()));
        letter.touch();
        if (done == items.size()) {
            return new Step(null, talkOf(letter, question, false));
        }

        return new Step(fillRequest(letter, loaded, question, items.get(done)), null);

    }

    private CoverLetterFillRequest fillRequest(CoverLetter letter, List<CoverLetterQuestion> loaded,
                                               CoverLetterQuestion target, MaterialItem item) {

        return new CoverLetterFillRequest(letter.getId(), letter.getJobRole(), letter.memoForWriting(),
                letter.strengthCodes(), CoverLetterPosting.inputOf(letter), CoverLetterPolishService.othersOf(loaded, target),
                CoverLetterPolishService.targetOf(target, CoverLetterPolishService.cardsOf(cardMaterials, target)),
                item.name(), blankFor(target.getDraftText(), item));

    }

    private CoverLetterTurnRequest request(UUID letterId, UUID questionId, Long userId, TurnTrigger trigger,
                                           String text) {

        CoverLetter letter = letters.findByPublicIdAndUserId(letterId, userId)
                .orElseThrow(() -> notFound(letterId, userId, AuditAction.EDIT_COVER_LETTER));
        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        CoverLetterQuestion target = targetOf(loaded, questionId);
        requireDrafted(letter);

        List<CoverLetterTurnRequest.Turn> thread = new ArrayList<>(turns.findByCoverLetterIdOrderBySeq(letter.getId())
                .stream()
                .map(turn -> new CoverLetterTurnRequest.Turn(turn.getSeq(), turn.getRole().name(),
                        turn.getQuestionSeq(), turn.getText(), turn.getItem() == null ? null : turn.getItem().name(),
                        turn.getStatus() == null ? null : turn.getStatus().name()))
                .toList());
        if (text != null) {
            int seq = thread.isEmpty() ? 1 : thread.getLast().seq() + 1;
            thread.add(new CoverLetterTurnRequest.Turn(seq, TurnRole.USER.name(), target.getSeq(), text, null, null));
        }

        CoverLetterPolishRequest.Target material = CoverLetterPolishService.targetOf(target,
                CoverLetterPolishService.cardsOf(cardMaterials, target));
        // 초안이 짐작으로 둔 증거(missing_evidence)는 저장된 곳이 없어 빈 목록으로 보낸다
        CoverLetterTurnRequest.Question question = new CoverLetterTurnRequest.Question(material.questionId(),
                material.text(), material.charLimit(), material.questionTheme(), material.primaryCard(),
                material.supportingCard(), material.answers(), material.currentText(),
                DraftText.blankTextsIn(material.currentText()), List.of());

        return new CoverLetterTurnRequest(letter.getId(), letter.getJobRole(), letter.memoForWriting(),
                letter.strengthCodes(), CoverLetterPosting.inputOf(letter), question, CoverLetterPolishService.othersOf(loaded, target),
                List.copyOf(thread), trigger.name());

    }

    // 이상한 응답은 다시 불러도 같아 전역 처리기가 500 으로 남기게 그대로 던진다
    private CoverLetterTurnReply next(CoverLetterTurnRequest request) {

        AtomicReference<CoverLetterTurnReply> result = new AtomicReference<>();
        try {
            AiCallContext.runForCoverLetter(request.coverLetterId(), () -> result.set(turnPort.next(request)));
        } catch (AgentUnavailableException failure) {
            if (failure.isRetryable()) {
                throw new CoverLetterAiUnavailableException("답을 받지 못했습니다. 잠시 뒤 다시 보내 주세요.", failure);
            }
            throw failure;
        }
        return result.get();

    }

    private Saved save(UUID letterId, UUID questionId, Long userId, String text, CoverLetterTurnReply reply) {

        CoverLetter letter = lockOwned(letterId, userId);
        requireDrafted(letter);
        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        CoverLetterQuestion target = targetOf(loaded, questionId);

        // 자소서 행을 잠근 채 마지막 순번을 읽어, 같은 자소서의 두 요청이 같은 순번을 쓰지 않는다
        List<CoverLetterTurn> thread = turns.findByCoverLetterIdOrderBySeq(letter.getId());
        int seq = thread.isEmpty() ? 0 : thread.getLast().getSeq();
        if (text != null) {
            turns.save(CoverLetterTurn.user(letter.getId(), target.getSeq(), ++seq, text));
        }
        TurnStatus status = TurnStatus.valueOf(reply.status());
        CoverLetterTurnReply.Message message = reply.message();
        turns.save(CoverLetterTurn.ai(letter.getId(), message.questionId(), ++seq, message.text(),
                message.item() == null ? null : MaterialItem.valueOf(message.item()), status));
        letter.touch();

        if (status != TurnStatus.FILLED || reply.answers().isEmpty()) {
            return new Saved(null, List.of(), talkOf(letter, target, false));
        }

        // 빈칸은 항목 순서대로 채워야 앞 항목을 채운 글 위에 다음 항목이 이어진다
        List<CoverLetterTurnReply.Answer> ordered = reply.answers().stream()
                .sorted(Comparator.comparing(answer -> MaterialItem.valueOf(answer.item())))
                .toList();
        List<MaterialItem> items = new ArrayList<>();
        for (CoverLetterTurnReply.Answer answer : ordered) {
            MaterialItem item = MaterialItem.valueOf(answer.item());
            remember(target, item, target.fillAnswer(item, answer.text()));
            items.add(item);
        }

        return new Saved(fillRequest(letter, loaded, target, items.getFirst()), List.copyOf(items), null);

    }

    // 초안 시작 때 기억하는 규칙과 같게, 경험 자체에 대한 답만 살아 있는 중심 카드에 남긴다
    private void remember(CoverLetterQuestion question, MaterialItem item, String text) {

        Long cardId = question.getPrimaryCardId();
        if (cardId == null || !CardMaterialAnswer.remembers(item)
                || !cardLabels.liveLabels(Set.of(cardId)).containsKey(cardId)) {
            return;
        }
        rememberedAnswers.remember(cardId, item.name(), text, CoverLetterTime.now());

    }

    // 그 항목의 빈칸이 글에 있으면 그 자리를, 없으면 남은 빈칸이 하나일 때만 그 자리를 채우게 하고 나머지는 AI 가 고른다
    private static String blankFor(String text, MaterialItem item) {

        String own = DraftText.blankOf(item);
        if (text.contains(own)) {
            return own;
        }
        List<String> left = DraftText.blankTextsIn(text);
        return left.size() == 1 ? left.getFirst() : null;

    }

    private Optional<CoverLetterFill> rewrite(CoverLetterFillRequest request) {

        AtomicReference<CoverLetterFill> result = new AtomicReference<>();
        try {
            AiCallContext.runForCoverLetter(request.coverLetterId(), () -> result.set(turnPort.fill(request)));
        } catch (AgentUnavailableException failure) {
            log.warn("자소서 대화 답으로 다시 쓰기 실패 coverLetterId={} questionId={}: {}", request.coverLetterId(),
                    request.target().questionId(), failure.getMessage());
            return Optional.empty();
        }
        return Optional.of(result.get());

    }

    /** 자소서 행을 잠근 채 불러야 마지막 순번을 읽어 같은 순번을 쓰는 요청이 없다 */
    void appendAi(CoverLetter letter, int questionSeq, String text, TurnStatus status) {

        List<CoverLetterTurn> thread = turns.findByCoverLetterIdOrderBySeq(letter.getId());
        int seq = thread.isEmpty() ? 0 : thread.getLast().getSeq();
        turns.save(CoverLetterTurn.ai(letter.getId(), questionSeq, seq + 1, text, null, status));

    }

    CoverLetterTalk talkOf(CoverLetter letter, CoverLetterQuestion target, boolean rewriteFailed) {

        CoverLetterView view = views.of(letter);
        Map<Integer, UUID> questionIds = view.questions().stream()
                .collect(Collectors.toMap(CoverLetterView.Question::seq, CoverLetterView.Question::id));
        CoverLetterView.Question question = view.questions().stream()
                .filter(each -> each.id().equals(target.getPublicId()))
                .findFirst()
                .orElseThrow();

        return new CoverLetterTalk(turnViews(letter.getId(), questionIds), question, rewriteFailed);

    }

    private List<CoverLetterTurnView> turnViews(Long letterId, Map<Integer, UUID> questionIds) {

        return turns.findByCoverLetterIdOrderBySeq(letterId).stream()
                .map(turn -> new CoverLetterTurnView(turn.getSeq(), turn.getRole(),
                        questionIds.get(turn.getQuestionSeq()), turn.getText(), turn.getItem(), turn.getStatus(),
                        turn.getCreatedAt()))
                .toList();

    }

    private static CoverLetterQuestion targetOf(List<CoverLetterQuestion> loaded, UUID questionId) {

        return loaded.stream()
                .filter(question -> question.getPublicId().equals(questionId))
                .findFirst()
                .orElseThrow(CoverLetterNotFoundException::new);

    }

    private CoverLetterQuestion questionOf(CoverLetter letter, UUID questionId) {

        return questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);

    }

    // DB 가 U+0000 을 저장하지 못해, 그대로 두면 저장 때 500 이 된다
    private static void checkText(String text) {

        if (text == null || text.isBlank()) {
            throw new InvalidCoverLetterInputException("보낼 말이 비었습니다.");
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new InvalidCoverLetterInputException("보낼 말에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        if (text.length() > TEXT_MAX_LENGTH) {
            throw new InvalidCoverLetterInputException("보낼 말은 " + TEXT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

    }

    private static void requireDrafted(CoverLetter letter) {

        if (letter.getStage() != CoverLetterStage.DRAFTED) {
            throw new CoverLetterStateException("대화는 초안을 다 쓴 뒤에만 할 수 있습니다.");
        }

    }

    private CoverLetter lockOwned(UUID publicId, Long userId) {

        return letters.findWithLockByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> notFound(publicId, userId, AuditAction.EDIT_COVER_LETTER));

    }

    private CoverLetterNotFoundException notFound(UUID publicId, Long userId, AuditAction action) {

        letters.findByPublicId(publicId).ifPresent(others -> auditLog.denied(userId, action, others.getId()));
        return new CoverLetterNotFoundException();

    }

    private record Saved(CoverLetterFillRequest fill, List<MaterialItem> items, CoverLetterTalk talk) {
    }

    private record Step(CoverLetterFillRequest next, CoverLetterTalk talk) {
    }
}
