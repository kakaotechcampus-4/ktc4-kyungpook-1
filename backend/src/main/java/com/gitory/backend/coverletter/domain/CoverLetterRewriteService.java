package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsPort;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardLabel;
import com.gitory.backend.card.domain.CardLabelReader;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 사용자가 고른 확정 카드를 문항의 중심 카드로 두고, 그 카드로 문항 하나만 처음부터 다시 써 지금 글을 바꾼다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 열고, 다시 쓰지 못하면 카드도 글도 바꾸지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverLetterRewriteService {

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CardLabelReader cardLabels;
    private final CardMaterialReader cardMaterials;
    private final CoverLetterDraftsPort drafts;
    private final CoverLetterTurnService turns;
    private final UserRateLimiter rateLimiter;
    private final AuditLog auditLog;
    private final PlatformTransactionManager transactionManager;

    /** 카드가 내 확정 카드가 아니면 InvalidCoverLetterInputException, 초안을 받은 뒤가 아니면 CoverLetterStateException 이다 */
    public CoverLetterTalk rewrite(UUID letterId, UUID questionId, Long userId, UUID cardId) {

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        Prepared prepared = read.execute(status -> prepare(letterId, questionId, userId, cardId));

        rateLimiter.acquire(RateLimitedAction.COVER_LETTER_REWRITE, userId, 1);
        Optional<CoverLetterDrafts.Draft> draft = write(prepared.request());

        return new TransactionTemplate(transactionManager).execute(status -> {
            CoverLetter letter = lockOwned(letterId, userId);
            requireDrafted(letter);
            CoverLetterQuestion question = questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                    .orElseThrow(CoverLetterNotFoundException::new);
            // 다시 쓰는 동안 사용자가 글을 고쳤거나 카드를 지웠으면 그 글을 덮지 않는다
            boolean applied = draft.isPresent() && Objects.equals(question.getDraftText(), prepared.draftBefore())
                    && cardLabels.confirmedOf(userId, cardId).isPresent();
            if (applied) {
                question.chooseCards(prepared.cardId(), null);
                CoverLetterDraftStep.apply(question, draft.get());
                question.discardPolish();
                turns.appendAi(letter, question.getSeq(),
                        "Q" + question.getSeq() + " 을 '" + prepared.cardTitle() + "' 카드로 다시 썼어요", TurnStatus.DONE);
                letter.touch();
            }
            return turns.talkOf(letter, question, !applied);
        });

    }

    private Prepared prepare(UUID letterId, UUID questionId, Long userId, UUID cardId) {

        CoverLetter letter = letters.findByPublicIdAndUserId(letterId, userId)
                .orElseThrow(() -> notFound(letterId, userId));
        CoverLetterQuestion target = questions.findByPublicIdAndCoverLetterId(questionId, letter.getId())
                .orElseThrow(CoverLetterNotFoundException::new);
        requireDrafted(letter);
        CardLabel card = cardLabels.confirmedOf(userId, cardId)
                .orElseThrow(() -> new InvalidCoverLetterInputException("내 확정 카드만 고를 수 있습니다."));
        CardMaterial material = cardMaterials.readOf(List.of(card.id())).stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCoverLetterInputException("내 확정 카드만 고를 수 있습니다."));

        CoverLetterDraftsRequest request = new CoverLetterDraftsRequest(letter.getId(), letter.getJobRole(),
                letter.memoForWriting(), letter.strengthCodes(),
                List.of(CoverLetterDraftStep.requestOf(target, material, null)), CoverLetterPosting.inputOf(letter));

        return new Prepared(request, card.id(), card.title(), target.getDraftText());

    }

    private Optional<CoverLetterDrafts.Draft> write(CoverLetterDraftsRequest request) {

        AtomicReference<CoverLetterDrafts> result = new AtomicReference<>();
        try {
            AiCallContext.runForCoverLetter(request.coverLetterId(), () -> result.set(drafts.write(request)));
        } catch (AgentUnavailableException failure) {
            log.warn("고른 카드로 문항 다시 쓰기 실패 coverLetterId={}: {}", request.coverLetterId(), failure.getMessage());
            return Optional.empty();
        }
        return Optional.of(result.get().drafts().getFirst());

    }

    private static void requireDrafted(CoverLetter letter) {

        if (letter.getStage() != CoverLetterStage.DRAFTED) {
            throw new CoverLetterStateException("카드로 다시 쓰기는 초안을 다 쓴 뒤에만 할 수 있습니다.");
        }

    }

    private CoverLetter lockOwned(UUID letterId, Long userId) {

        return letters.findWithLockByPublicIdAndUserId(letterId, userId)
                .orElseThrow(() -> notFound(letterId, userId));

    }

    private CoverLetterNotFoundException notFound(UUID letterId, Long userId) {

        letters.findByPublicId(letterId).ifPresent(others -> auditLog.denied(userId, AuditAction.EDIT_COVER_LETTER,
                others.getId()));
        return new CoverLetterNotFoundException();

    }

    private record Prepared(CoverLetterDraftsRequest request, Long cardId, String cardTitle, String draftBefore) {
    }
}
