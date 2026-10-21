package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionPort;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionResult;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardGenerationGuard;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.recommend.domain.CandidateReference;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import com.gitory.backend.recommend.domain.CandidateType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 사용자가 고른 칸에 대해 AI 에게 되묻기 질문을 받아 저장한다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 연다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewAskService {

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final CandidateReferenceService candidates;
    private final InterviewContextAssembler context;
    private final InterviewQuestionPort questions;
    private final InterviewLimits limits;
    private final PlatformTransactionManager transactionManager;
    private final UserRateLimiter rateLimiter;

    /**
     * 초안을 만드는 중이거나 확정된 카드는 409, 답하지 않은 질문이 있으면 그 질문(AI 를 부르지 않음), 질문 상한이면 409, 칸이 잘못되면 400 순서로 확인한다
     * 새 질문은 AI 를 다녀온 뒤 같은 확인을 한 번 더 하고 저장한다
     */
    public InterviewTurnView ask(UUID publicId, Long userId, String field) {

        Prepared prepared = snapshot().execute(status -> prepare(publicId, userId, field));

        if (prepared.openTurn() != null) {
            return prepared.openTurn();
        }

        // 남아 있던 질문을 돌려줄 때는 AI 를 부르지 않으므로, 새 질문을 만들 때만 센다
        rateLimiter.acquire(RateLimitedAction.INTERVIEW_ASK, userId, 1);
        InterviewQuestionResult question = fetchQuestion(prepared.request(), prepared.slot());

        return save(prepared, publicId, userId, question);

    }

    // 카드·질문·문장·후보·커밋을 한 시점으로 읽도록 REPEATABLE READ 로 열고, AI 를 부르기 전에 닫는다
    private TransactionTemplate snapshot() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return template;

    }

    private Prepared prepare(UUID publicId, Long userId, String field) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.EDIT_CARD);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        Optional<InterviewTurn> open = openTurnOrRefuse(card, asked);
        if (open.isPresent()) {
            return Prepared.open(viewOf(open.get(), sourceTypeOf(card)));
        }

        StarField slot = slotOf(field);

        return Prepared.toAsk(card, slot, sourceTypeOf(card), context.assemble(card, slot, asked.size()));

    }

    private InterviewQuestionResult fetchQuestion(InterviewQuestionRequest request, StarField slot) {

        InterviewQuestionResult result;
        try {
            result = questions.nextQuestion(request);
        } catch (AgentUnavailableException failure) {
            throw failureOf(request, failure);
        }

        // 질문 상한 안에서 요청했으므로 AI 의 COMPLETE 는 상한 409 가 아니라 이상한 응답이다
        if (result.nextAction() != InterviewNextAction.ASK_AGAIN) {
            throw new InvalidInterviewResponseException("질문을 더 만들 수 있는데 AI 가 COMPLETE 를 돌려줬다");
        }

        if (result.targetStarSlot() != InterviewStarSlot.valueOf(slot.name())) {
            throw new InvalidInterviewResponseException("AI 가 요청하지 않은 칸의 질문을 돌려줬다");
        }

        return result;

    }

    private RuntimeException failureOf(InterviewQuestionRequest request, AgentUnavailableException failure) {

        if (failure.isRetryable()) {
            log.warn("카드 {} 의 되묻기 질문을 AI 에게서 받지 못했다", request.cardId(), failure);
            return new InterviewAiUnavailableException(failure);
        }

        return new InvalidInterviewResponseException("AI 가 이상한 응답을 돌려줬다", failure);

    }

    private InterviewTurnView save(Prepared prepared, UUID publicId, Long userId, InterviewQuestionResult question) {

        try {
            // 기본 격리(READ COMMITTED)로 열어야 잠금을 기다린 뒤의 확인이 먼저 커밋된 질문을 본다
            return new TransactionTemplate(transactionManager)
                    .execute(status -> saveQuestion(publicId, userId, prepared.slot(), prepared.sourceType(), question));
        } catch (DataIntegrityViolationException raced) {
            // 카드 행 잠금 때문에 API 로는 닿지 않는 갈래이고, UNIQUE(card_id, seq) 가 마지막 방어선이다
            return openTurnOf(turns.findByCardIdOrderBySeqAsc(prepared.card().getId()))
                    .map(turn -> viewOf(turn, prepared.sourceType()))
                    .orElseThrow(() -> raced);
        }

    }

    private InterviewTurnView saveQuestion(UUID publicId, Long userId, StarField slot, InterviewSourceType sourceType,
                                           InterviewQuestionResult question) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        Optional<InterviewTurn> open = openTurnOrRefuse(card, asked);
        if (open.isPresent()) {
            return viewOf(open.get(), sourceType);
        }

        InterviewTurn saved = turns.save(InterviewTurn.ask(card.getId(), nextSeq(asked), slot,
                QuestionType.valueOf(question.questionType().name()), question.questionText()));

        return viewOf(saved, sourceType);

    }

    // 읽기와 저장 두 단계가 같은 순서로 확인하도록 한곳에 둔다
    private Optional<InterviewTurn> openTurnOrRefuse(Card card, List<InterviewTurn> asked) {

        generation.requireNotGenerating(card);
        card.requireDraft();

        Optional<InterviewTurn> open = openTurnOf(asked);
        if (open.isEmpty() && asked.size() >= limits.maxTurns()) {
            throw new InterviewCapReachedException();
        }

        return open;

    }

    private static Optional<InterviewTurn> openTurnOf(List<InterviewTurn> asked) {

        return asked.stream()
                .filter(turn -> !turn.isAnswered())
                .findFirst();

    }

    private static StarField slotOf(String field) {

        return Arrays.stream(StarField.values())
                .filter(slot -> slot.name().equals(field))
                .findFirst()
                .orElseThrow(InvalidInterviewFieldException::new);

    }

    private static short nextSeq(List<InterviewTurn> asked) {

        int max = asked.stream().mapToInt(InterviewTurn::getSeq).max().orElse(0);

        return (short) (max + 1);

    }

    private InterviewSourceType sourceTypeOf(Card card) {

        CandidateType type = Optional.ofNullable(card.getCandidateId())
                .flatMap(candidates::find)
                .map(CandidateReference::type)
                .orElse(null);

        return InterviewSourceType.of(type);

    }

    private InterviewTurnView viewOf(InterviewTurn turn, InterviewSourceType sourceType) {

        return InterviewTurnView.of(turn, sourceType, limits.maxTurns());

    }

    private record Prepared(Card card, StarField slot, InterviewSourceType sourceType,
                            InterviewQuestionRequest request, InterviewTurnView openTurn) {

        static Prepared open(InterviewTurnView turn) {

            return new Prepared(null, null, null, null, turn);

        }

        static Prepared toAsk(Card card, StarField slot, InterviewSourceType sourceType,
                              InterviewQuestionRequest request) {

            return new Prepared(card, slot, sourceType, request, null);

        }
    }
}
