package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewAnswerOutcome;
import com.gitory.backend.agent.port.InterviewAnswerReviewPort;
import com.gitory.backend.agent.port.InterviewAnswerReviewRequest;
import com.gitory.backend.agent.port.InterviewAnswerReviewResult;
import com.gitory.backend.agent.port.InterviewAnswerSource;
import com.gitory.backend.agent.port.InterviewCurrentTurn;
import com.gitory.backend.agent.port.InterviewFollowUp;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.agent.port.InterviewStatementTarget;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 저장한 되묻기 답을 AI 가 평가해 모자라다고 하면 같은 칸의 질문을 하나 더 만든다
 * 평가가 실패해도 답은 이미 저장돼 있으므로 어떤 실패도 답 저장 응답을 바꾸지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties({InterviewAnswerReviewProperties.class, InterviewLimits.class})
public class InterviewAnswerReviewService {

    private final InterviewAnswerReviewProperties properties;
    private final CardOwnershipService ownership;
    private final InterviewTurnRepository turns;
    private final InterviewContextAssembler context;
    private final InterviewAnswerReviewPort review;
    private final InterviewLimits limits;
    private final PlatformTransactionManager transactionManager;

    /**
     * 답한 질문이 카드의 마지막 질문이고 질문 상한 아래일 때만 AI 를 부른다
     * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 연다
     */
    public void askAgainIfShort(UUID publicId, Long userId, int turnNo) {

        if (!properties.enabled()) {
            return;
        }

        try {
            reviewAndSave(publicId, userId, turnNo);
        } catch (RuntimeException failure) {
            // 답은 이미 커밋됐으므로 어떤 실패도 밖으로 내지 않고, 답 원문이 든 예외 메시지는 남기지 않는다
            log.warn("카드 {} 의 되묻기 답 평가를 마치지 못해 다시 묻지 않는다 ({})", publicId, failure.getClass().getSimpleName());
        }

    }

    private void reviewAndSave(UUID publicId, Long userId, int turnNo) {

        Optional<InterviewAnswerReviewRequest> request = snapshot().execute(status -> prepare(publicId, userId, turnNo));
        if (request.isEmpty()) {
            return;
        }

        InterviewAnswerReviewResult result = review.review(request.get());

        InterviewFollowUp followUp = result.followUp();
        if (result.outcome() != InterviewAnswerOutcome.INSUFFICIENT || followUp == null
                || followUp.target().starSlot() != request.get().currentTurn().target().starSlot()) {
            return;
        }

        // 기본 격리(READ COMMITTED)로 열어야 잠금을 기다린 뒤의 확인이 먼저 커밋된 질문을 본다
        // UNIQUE(card_id, seq) 가 마지막 방어선이라 먼저 저장된 질문과 겹치면 저장이 실패하고, 그 실패는 askAgainIfShort 가 삼킨다
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> saveFollowUp(publicId, userId, turnNo, followUp));

    }

    // 카드·질문·후보·커밋을 한 시점으로 읽도록 REPEATABLE READ 로 열고, AI 를 부르기 전에 닫는다
    private TransactionTemplate snapshot() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return template;

    }

    private Optional<InterviewAnswerReviewRequest> prepare(UUID publicId, Long userId, int turnNo) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.EDIT_CARD);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());
        Optional<InterviewTurn> answered = answeredLastTurn(card, asked, turnNo);
        if (answered.isEmpty()) {
            return Optional.empty();
        }

        InterviewTurn turn = answered.get();
        InterviewQuestionRequest base = context.assemble(card, turn.getStarSlot(), asked.size());
        InterviewStarSlot slot = InterviewStarSlot.valueOf(turn.getStarSlot().name());
        int statementSeq = base.missingSlots().get(0).statementSeq();

        return Optional.of(new InterviewAnswerReviewRequest(card.getId(), base.sourceType(), base.candidate(),
                new InterviewCurrentTurn(new InterviewStatementTarget(slot, statementSeq),
                        InterviewQuestionType.valueOf(turn.getQuestionType().name()), turn.getQuestionText()),
                turn.getAnswerText(), InterviewAnswerSource.valueOf(turn.getAnswerSource().name()), List.of(),
                asked.size(), limits.maxTurns()));

    }

    private void saveFollowUp(UUID publicId, Long userId, int turnNo, InterviewFollowUp followUp) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());
        Optional<InterviewTurn> answered = answeredLastTurn(card, asked, turnNo);
        if (answered.isEmpty()) {
            return;
        }

        InterviewTurn parent = answered.get();
        turns.save(InterviewTurn.followUp(card.getId(), (short) (parent.getSeq() + 1), parent.getStarSlot(),
                QuestionType.valueOf(followUp.questionType().name()), followUp.questionText(), parent.getId()));

    }

    // 이미 다음 질문이 생긴 뒤 다시 온 같은 답, 후속 질문(AUTO)에 한 답, 확정된 카드, 상한에 닿은 카드는 다시 묻지 않는다
    private Optional<InterviewTurn> answeredLastTurn(Card card, List<InterviewTurn> asked, int turnNo) {

        if (card.isConfirmed() || asked.size() >= limits.maxTurns() || asked.isEmpty()) {
            return Optional.empty();
        }

        InterviewTurn last = asked.get(asked.size() - 1);

        return last.getSeq() == turnNo && last.isAnswered() && last.getTriggerSource() != TriggerSource.AUTO
                ? Optional.of(last) : Optional.empty();

    }
}
