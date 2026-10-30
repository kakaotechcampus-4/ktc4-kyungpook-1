package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewAnswerMode;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionPort;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionResult;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardGenerationGuard;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ai.AiCallContext;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 되묻기 질문을 AI 에게 받아 저장하고, 칸을 지정하지 않으면 고를 수 있는 칸 중 AI 가 다음 칸을 고른다
 * 같은 칸 2번·넘김·측정 안 함 같은 규칙은 이 서비스가 칸 목록으로 걸러 AI 에 주고, AI 답이 그 안에 있는지 다시 본다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 연다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewAskService {

    private static final int MAX_ASKS_PER_SLOT = 2;
    private static final Set<TurnOutcome> CLOSING_OUTCOMES = EnumSet.of(TurnOutcome.SKIPPED, TurnOutcome.NOT_MEASURED);

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final CandidateReferenceService candidates;
    private final InterviewContextAssembler context;
    private final InterviewQuestionPort questions;
    private final InterviewLimits limits;
    private final PlatformTransactionManager transactionManager;
    private final UserRateLimiter rateLimiter;
    private final CardVersionReader reader;

    /**
     * 초안을 만드는 중이거나 확정된 카드는 409, 답을 기다리는 질문이 있으면 그 질문(AI 를 부르지 않음), 질문 상한이면 409,
     * 칸이 잘못되면 400, 지정한 칸이 닫혔으면 409 순서로 확인한다
     * 칸을 지정하지 않았는데 고를 칸이 없거나 AI 가 끝을 고르면 질문을 만들지 않고 null 을 돌려주며, AI 가 끝낸 이유를 남기고 카드 상태를 다시 정한다
     * 새 질문은 AI 를 다녀온 뒤 같은 확인을 한 번 더 하고 저장한다
     */
    public InterviewTurnView ask(UUID publicId, Long userId, String field) {

        Prepared prepared = snapshot().execute(status -> prepare(publicId, userId, field));

        if (prepared.openTurn() != null) {
            return prepared.openTurn();
        }
        if (prepared.request() == null) {
            settleAtEnd(publicId, userId);
            return null;
        }

        // 남아 있던 질문을 돌려줄 때는 AI 를 부르지 않으므로, 새 질문을 만들 때만 센다
        rateLimiter.acquire(RateLimitedAction.INTERVIEW_ASK, userId, 1);
        InterviewQuestionResult question = fetchQuestion(prepared.request(), prepared.target(), prepared.allowed());
        if (question.nextAction() != InterviewNextAction.ASK_AGAIN) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> recordEnd(publicId, userId, question.reason()));
            return null;
        }

        return save(prepared, publicId, userId, question);

    }

    // 답마다 확정하면 다음 질문이 확정 카드라 막히므로, 되묻기가 끝났을 때 한 번 그 글로 카드 상태를 정한다
    private void settleAtEnd(UUID publicId, Long userId) {

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
            card.settle(reader.read(card.getId(), card.getCurrentVersion()));
        });

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

        StarField target = field == null ? null : slotOf(field);
        List<StarField> allowed = allowedSlots(asked);
        if (target != null && !allowed.contains(target)) {
            throw new InterviewFieldClosedException();
        }
        if (allowed.isEmpty()) {
            return Prepared.done();
        }

        return Prepared.toAsk(card, target, allowed, sourceTypeOf(card), context.assemble(card, allowed, target, asked));

    }

    private InterviewQuestionResult fetchQuestion(InterviewQuestionRequest request, StarField target,
                                                  List<StarField> allowed) {

        InterviewQuestionResult result;
        try {
            result = AiCallContext.callForCard(request.cardId(), () -> questions.nextQuestion(request));
        } catch (AgentUnavailableException failure) {
            throw failureOf(request, failure);
        }

        // 칸을 지정했으면 그 칸을 묻기로 했으므로 COMPLETE 는 이상한 응답이고, 지정하지 않았으면 더 물을 것이 없다는 판단이다
        if (result.nextAction() != InterviewNextAction.ASK_AGAIN) {
            if (target != null) {
                throw new InvalidInterviewResponseException("칸을 지정했는데 AI 가 COMPLETE 를 돌려줬다");
            }
            return result;
        }

        StarField chosen = StarField.valueOf(result.targetStarSlot().name());
        if (!allowed.contains(chosen) || (target != null && chosen != target)) {
            throw new InvalidInterviewResponseException("AI 가 고를 수 없는 칸의 질문을 돌려줬다");
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
                    .execute(status -> saveQuestion(publicId, userId, prepared.target(), prepared.sourceType(), question));
        } catch (DataIntegrityViolationException raced) {
            // 카드 행 잠금 때문에 API 로는 닿지 않는 갈래이고, UNIQUE(card_id, seq) 가 마지막 방어선이다
            return openTurnOf(turns.findByCardIdOrderBySeqAsc(prepared.card().getId()))
                    .map(turn -> viewOf(turn, prepared.sourceType()))
                    .orElseThrow(() -> raced);
        }

    }

    private InterviewTurnView saveQuestion(UUID publicId, Long userId, StarField target, InterviewSourceType sourceType,
                                           InterviewQuestionResult question) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        Optional<InterviewTurn> open = openTurnOrRefuse(card, asked);
        if (open.isPresent()) {
            return viewOf(open.get(), sourceType);
        }

        // AI 를 기다리는 사이 다른 창에서 그 칸을 넘기거나 두 번 물었으면 닫힌 칸이다
        StarField slot = StarField.valueOf(question.targetStarSlot().name());
        if (!allowedSlots(asked).contains(slot)) {
            throw new InterviewFieldClosedException();
        }

        InterviewTurn saved = turns.save(InterviewTurn.asked(card.getId(), nextSeq(asked), slot,
                QuestionType.valueOf(question.questionType().name()), question.questionText(), target != null,
                lastTurnId(asked), answerModeOf(slot, question), question.suggestedAnswers(), question.reason()));
        card.resumeInterview();

        return viewOf(saved, sourceType);

    }

    // AI 를 기다리는 사이 다른 창에서 새 질문이 생겼으면 되묻기가 끝나지 않았으므로 이유도 남기지 않고 상태도 정하지 않는다
    private void recordEnd(UUID publicId, Long userId, String reason) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        if (openTurnOf(turns.findByCardIdOrderBySeqAsc(card.getId())).isEmpty()) {
            card.endInterview(reason);
            card.settle(reader.read(card.getId(), card.getCurrentVersion()));
        }

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
                .filter(turn -> !turn.isClosed())
                .findFirst();

    }

    // 같은 칸은 두 번까지 묻고, 측정 안 함·넘김으로 끝난 칸은 다시 묻지 않는다
    private static List<StarField> allowedSlots(List<InterviewTurn> asked) {

        return Arrays.stream(StarField.values())
                .filter(slot -> asked.stream().filter(turn -> turn.getStarSlot() == slot).count() < MAX_ASKS_PER_SLOT)
                .filter(slot -> asked.stream().noneMatch(turn -> turn.getStarSlot() == slot
                        && CLOSING_OUTCOMES.contains(turn.getOutcome())))
                .toList();

    }

    // 수치 버튼은 R 칸에만 맞으므로 다른 칸에 MEASURE 가 와도 글로 답하는 질문으로 둔다
    private static AnswerMode answerModeOf(StarField slot, InterviewQuestionResult question) {

        boolean measure = slot == StarField.R && question.answerMode() == InterviewAnswerMode.MEASURE;

        return measure ? AnswerMode.MEASURE : AnswerMode.TEXT;

    }

    private static Long lastTurnId(List<InterviewTurn> asked) {

        return asked.isEmpty() ? null : asked.get(asked.size() - 1).getId();

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

    private record Prepared(Card card, StarField target, List<StarField> allowed, InterviewSourceType sourceType,
                            InterviewQuestionRequest request, InterviewTurnView openTurn) {

        static Prepared open(InterviewTurnView turn) {

            return new Prepared(null, null, List.of(), null, null, turn);

        }

        static Prepared done() {

            return new Prepared(null, null, List.of(), null, null, null);

        }

        static Prepared toAsk(Card card, StarField target, List<StarField> allowed, InterviewSourceType sourceType,
                              InterviewQuestionRequest request) {

            return new Prepared(card, target, allowed, sourceType, request, null);

        }
    }
}
