package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterPolish;
import com.gitory.backend.agent.port.CoverLetterPolishPort;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 초안을 받은 자소서의 문항 하나를 AI 에게 진단·첨삭받아 미리 보기로 두고, 사용자가 고르면 적용하거나 버린다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 열고, AI 를 부를 때만 호출 제한을 센다
 */
@Service
@RequiredArgsConstructor
public class CoverLetterPolishService {

    private static final String INFERRED = "INFERRED";

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final CoverLetterPolishPort polishes;
    private final UserRateLimiter rateLimiter;
    private final AuditLog auditLog;
    private final CoverLetterViewFactory views;
    private final PlatformTransactionManager transactionManager;

    /** 앞 첨삭 결과는 덮고, AI 가 잠시 응답하지 못하면 아무것도 저장하지 않고 다시 요청할 수 있는 실패로 끝낸다 */
    public CoverLetterView polish(UUID letterId, UUID questionId, Long userId) {

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        CoverLetterPolishRequest request = read.execute(status -> request(letterId, questionId, userId));

        rateLimiter.acquire(RateLimitedAction.COVER_LETTER_POLISH, userId, 1);
        CoverLetterPolish result = fetch(request);

        return new TransactionTemplate(transactionManager).execute(status -> {
            CoverLetter letter = lockOwned(letterId, userId);
            CoverLetterQuestion question = questionOf(letter, questionId);
            requireDrafted(letter);
            question.polished(polishOf(result));
            letter.touch();
            return views.of(letter);
        });

    }

    @Transactional
    public CoverLetterView applyPolish(UUID letterId, UUID questionId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId);
        CoverLetterQuestion question = questionOf(letter, questionId);
        requireDrafted(letter);

        question.applyPolish();
        letter.touch();

        return views.of(letter);

    }

    /** 첨삭 결과가 없어도 그대로 돌려줘, 화면이 두 번 눌러도 실패로 보이지 않는다 */
    @Transactional
    public CoverLetterView discardPolish(UUID letterId, UUID questionId, Long userId) {

        CoverLetter letter = lockOwned(letterId, userId);
        CoverLetterQuestion question = questionOf(letter, questionId);
        requireDrafted(letter);

        question.discardPolish();
        letter.touch();

        return views.of(letter);

    }

    private CoverLetterPolishRequest request(UUID letterId, UUID questionId, Long userId) {

        CoverLetter letter = letters.findByPublicIdAndUserId(letterId, userId).orElseThrow(() -> notFound(letterId, userId));
        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        CoverLetterQuestion target = loaded.stream()
                .filter(question -> question.getPublicId().equals(questionId))
                .findFirst()
                .orElseThrow(CoverLetterNotFoundException::new);
        requireDrafted(letter);
        // AI 는 빈 글을 첨삭하지 않아, 보내면 다시 해도 같은 실패가 된다
        if (target.getDraftText() == null || target.getDraftText().isBlank()) {
            throw new InvalidCoverLetterInputException("첨삭할 글이 비었습니다.");
        }

        return new CoverLetterPolishRequest(letter.getId(), letter.getJobRole(), letter.memoForWriting(),
                letter.strengthCodes(), CoverLetterPosting.inputOf(letter), othersOf(loaded, target),
                targetOf(target, cardsOf(cardMaterials, target)));

    }

    static Map<Long, CardMaterial> cardsOf(CardMaterialReader cardMaterials, CoverLetterQuestion target) {

        return cardMaterials.readOf(CoverLetterCardDetails.cardIdsOf(List.of(target))).stream()
                .collect(Collectors.toMap(CardMaterial::id, Function.identity()));

    }

    static List<CoverLetterPolishRequest.Other> othersOf(List<CoverLetterQuestion> loaded,
                                                         CoverLetterQuestion target) {

        return loaded.stream()
                .filter(question -> question != target && question.getDraftText() != null)
                .map(question -> new CoverLetterPolishRequest.Other(question.getSeq(), question.getText(),
                        question.getDraftText()))
                .toList();

    }

    static CoverLetterPolishRequest.Target targetOf(CoverLetterQuestion target, Map<Long, CardMaterial> cards) {

        CardMaterial primary = CoverLetterCardDetails.primaryOf(target, cards);

        return new CoverLetterPolishRequest.Target(target.getSeq(), target.getText(), target.getCharLimit(),
                target.getTheme() == null ? null : target.getTheme().name(),
                CoverLetterCardDetails.detail(primary),
                CoverLetterCardDetails.detail(CoverLetterCardDetails.supportingOf(target, cards)),
                CoverLetterDraftStep.answersOf(target, primary), target.getDraftText(), inferredSeqs(target));

    }

    // 사용자가 확인한 추론은 표시가 이미 지워져, 남은 INFERRED 표시가 아직 확인하지 않은 짐작 문장이다
    static List<Integer> inferredSeqs(CoverLetterQuestion question) {

        return question.getMarks().stream()
                .filter(mark -> INFERRED.equals(mark.code()))
                .map(DraftMark::sentenceSeq)
                .distinct()
                .sorted()
                .toList();

    }

    // 이상한 응답은 다시 불러도 같아 전역 처리기가 500 으로 남기게 그대로 던진다
    private CoverLetterPolish fetch(CoverLetterPolishRequest request) {

        AtomicReference<CoverLetterPolish> result = new AtomicReference<>();
        try {
            AiCallContext.runForCoverLetter(request.coverLetterId(), () -> result.set(polishes.polish(request)));
        } catch (AgentUnavailableException failure) {
            if (failure.isRetryable()) {
                throw new CoverLetterAiUnavailableException(failure);
            }
            throw failure;
        }
        return result.get();

    }

    private static QuestionPolish polishOf(CoverLetterPolish result) {

        return new QuestionPolish(result.summary(),
                result.points().stream()
                        .map(point -> new PolishPoint(point.seq(), PolishKind.valueOf(point.kind()),
                                PolishCategory.valueOf(point.category()), point.title(), point.detail(),
                                point.sentenceSeqs()))
                        .toList(),
                draftOf(result.draft()), CoverLetterTime.now());

    }

    static PolishedDraft draftOf(CoverLetterDrafts.Draft draft) {

        return new PolishedDraft(draft.text(),
                draft.sentences().stream().map(sentence -> new DraftSentence(sentence.seq(), sentence.text())).toList(),
                draft.charCount(), draft.overBy(),
                draft.marks().stream().map(mark -> new DraftMark(mark.sentenceSeq(), mark.code(), mark.value())).toList(),
                draft.blanks().stream().map(MaterialItem::valueOf).toList());

    }

    private CoverLetterQuestion questionOf(CoverLetter letter, UUID questionId) {

        return questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);

    }

    private static void requireDrafted(CoverLetter letter) {

        if (letter.getStage() != CoverLetterStage.DRAFTED) {
            throw new CoverLetterStateException("첨삭은 초안을 다 쓴 뒤에만 받을 수 있습니다.");
        }

    }

    private CoverLetter lockOwned(UUID publicId, Long userId) {

        return letters.findWithLockByPublicIdAndUserId(publicId, userId).orElseThrow(() -> notFound(publicId, userId));

    }

    private CoverLetterNotFoundException notFound(UUID publicId, Long userId) {

        letters.findByPublicId(publicId)
                .ifPresent(others -> auditLog.denied(userId, AuditAction.EDIT_COVER_LETTER, others.getId()));
        return new CoverLetterNotFoundException();

    }
}
