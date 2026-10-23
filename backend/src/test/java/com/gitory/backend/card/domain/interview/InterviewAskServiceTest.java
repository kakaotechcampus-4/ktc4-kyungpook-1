package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionPort;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionResult;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardGeneratingException;
import com.gitory.backend.card.domain.CardGenerationGuard;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 트랜잭션 경계와 오류 갈래처럼 DB 만으로는 보기 어려운 약속을 협력 객체를 대신 세워 확인한다 */
class InterviewAskServiceTest {

    private static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Long USER_ID = 7L;
    private static final Long CARD_ID = 5L;

    private final CardOwnershipService ownership = mock(CardOwnershipService.class);
    private final CardGenerationGuard generation = mock(CardGenerationGuard.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final CandidateReferenceService candidates = mock(CandidateReferenceService.class);
    private final InterviewContextAssembler assembler = mock(InterviewContextAssembler.class);
    private final InterviewQuestionPort port = mock(InterviewQuestionPort.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final List<TransactionStatus> statuses = new ArrayList<>();

    private InterviewQuestionRequest request;

    @BeforeEach
    void setUp() {

        Card card = Card.startManual(USER_ID, 11L, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(ownership.findOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD)).thenReturn(card);
        when(ownership.lockOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD)).thenReturn(card);

        request = new InterviewQuestionRequest(CARD_ID, null,
                List.of(new InterviewMissingSlot(InterviewStarSlot.T, 1, null, InterviewConfidence.LOW, List.of())),
                0, 2, InterviewCardSourceType.DIRECT_CARD);
        when(assembler.assemble(any(Card.class), any(StarField.class), anyInt())).thenReturn(request);
        when(port.nextQuestion(request)).thenReturn(question(InterviewStarSlot.T));

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of());
        when(turns.save(any(InterviewTurn.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            TransactionStatus status = new SimpleTransactionStatus();
            statuses.add(status);
            return status;
        });

    }

    @Test
    @DisplayName("카드·질문·문장은 읽기 전용 REPEATABLE READ 트랜잭션에서 읽고, AI 는 그 트랜잭션이 끝난 뒤에 부르며, 저장은 읽기 전용이 아닌 기본 격리 트랜잭션에서 카드 행을 잠그고 한다")
    void readsInSnapshotCallsAiAfterItAndSavesInDefaultIsolation() {

        service(2).ask(PUBLIC_ID, USER_ID, "T");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(transactionManager, ownership, assembler, port, turns);
        order.verify(transactionManager).getTransaction(definitions.capture());
        order.verify(ownership).findOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD);
        order.verify(assembler).assemble(any(Card.class), any(StarField.class), anyInt());
        order.verify(transactionManager).commit(statuses.get(0));
        order.verify(port).nextQuestion(request);
        order.verify(transactionManager).getTransaction(definitions.capture());
        order.verify(ownership).lockOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD);
        order.verify(turns).save(any(InterviewTurn.class));
        order.verify(transactionManager).commit(statuses.get(1));

        TransactionDefinition read = definitions.getAllValues().get(0);
        assertThat(read.isReadOnly()).isTrue();
        assertThat(read.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionDefinition write = definitions.getAllValues().get(1);
        assertThat(write.isReadOnly()).isFalse();
        assertThat(write.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);

    }

    @Test
    @DisplayName("답하지 않은 질문이 있으면 AI 를 부르지 않고 저장 트랜잭션도 열지 않는다")
    void openTurnNeedsNoSecondTransaction() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(InterviewTurnSamples.open(1)));

        InterviewTurnView view = service(2).ask(PUBLIC_ID, USER_ID, "T");

        assertThat(view.turnNo()).isEqualTo(1);
        verifyNoInteractions(port);
        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("저장이 UNIQUE(card_id, seq) 에 막히면 저장 트랜잭션이 되돌려진 뒤 다시 읽어 답하지 않은 질문을 돌려준다")
    void returnsOpenTurnAfterRolledBackUniqueViolation() {

        when(turns.save(any(InterviewTurn.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID))
                .thenReturn(List.of(), List.of(), List.of(InterviewTurnSamples.open(1)));

        InterviewTurnView view = service(2).ask(PUBLIC_ID, USER_ID, "T");

        assertThat(view.turnNo()).isEqualTo(1);
        assertThat(view.question()).isEqualTo("질문");
        InOrder order = inOrder(transactionManager, turns);
        order.verify(transactionManager).rollback(statuses.get(1));
        order.verify(turns).findByCardIdOrderBySeqAsc(CARD_ID);

    }

    @Test
    @DisplayName("UNIQUE 에 막혔는데 다시 읽어도 답하지 않은 질문이 없으면 그 예외를 그대로 다시 던진다")
    void rethrowsUniqueViolationWithoutOpenTurn() {

        DataIntegrityViolationException violation = new DataIntegrityViolationException("duplicate");
        when(turns.save(any(InterviewTurn.class))).thenThrow(violation);

        assertThatThrownBy(() -> service(2).ask(PUBLIC_ID, USER_ID, "T")).isSameAs(violation);

    }

    @Test
    @DisplayName("새 질문 번호는 질문 수가 아니라 가장 큰 질문 번호 + 1 이고, 칸·종류·글은 AI 가 준 대로, 질문한 쪽은 USER 로 저장한다")
    void numbersFromTheHighestSeq() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(InterviewTurnSamples.turn(2, StarField.S,
                QuestionType.EVIDENCE_GAP, TriggerSource.USER, "질문", TurnOutcome.ANSWERED, "답", AnswerSource.TYPED)));

        service(3).ask(PUBLIC_ID, USER_ID, "T");

        ArgumentCaptor<InterviewTurn> saved = ArgumentCaptor.forClass(InterviewTurn.class);
        verify(turns).save(saved.capture());
        assertThat(saved.getValue().getSeq()).isEqualTo((short) 3);
        assertThat(saved.getValue().getCardId()).isEqualTo(CARD_ID);
        assertThat(saved.getValue().getStarSlot()).isEqualTo(StarField.T);
        assertThat(saved.getValue().getQuestionType()).isEqualTo(QuestionType.RECALL_AID);
        assertThat(saved.getValue().getQuestionText()).isEqualTo("묻는 글");
        assertThat(saved.getValue().getTriggerSource()).isEqualTo(TriggerSource.USER);

    }

    @Test
    @DisplayName("질문 상한은 설정 값을 따라, 상한이 3 이면 질문이 둘일 때는 AI 에게 묻고 셋이면 InterviewCapReachedException 이다")
    void followsConfiguredMaxTurns() {

        List<InterviewTurn> two = List.of(answered(1), answered(2));
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(two);

        service(3).ask(PUBLIC_ID, USER_ID, "T");

        verify(port).nextQuestion(request);

        List<InterviewTurn> three = List.of(answered(1), answered(2), answered(3));
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(three);

        assertThatThrownBy(() -> service(3).ask(PUBLIC_ID, USER_ID, "T"))
                .isInstanceOf(InterviewCapReachedException.class);

    }

    @Test
    @DisplayName("초안을 만드는 중인 카드는 질문 상한에 닿았어도 CardGeneratingException 이고 AI 를 부르지 않는다")
    void refusesGeneratingCardBeforeCapAndWithoutCallingAi() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(answered(1), answered(2)));
        doThrow(new CardGeneratingException()).when(generation).requireNotGenerating(any(Card.class));

        assertThatThrownBy(() -> service(2).ask(PUBLIC_ID, USER_ID, "T"))
                .isInstanceOf(CardGeneratingException.class);

        verifyNoInteractions(port);

    }

    @Test
    @DisplayName("돌려주는 질문의 상한과 남은 질문 수도 설정 값을 따른다")
    void viewFollowsConfiguredMaxTurns() {

        InterviewTurnView view = service(3).ask(PUBLIC_ID, USER_ID, "T");

        assertThat(view.maxTurns()).isEqualTo(3);
        assertThat(view.remaining()).isEqualTo(2);

    }

    @Test
    @DisplayName("AI 가 다시 시도해도 되는 실패를 주면 원인을 담은 InterviewAiUnavailableException 이고 저장 트랜잭션은 열지 않는다")
    void retryableFailureBecomesUnavailable() {

        AgentUnavailableException failure = new AgentUnavailableException("끊김", true, false, null);
        when(port.nextQuestion(request)).thenThrow(failure);

        assertThatThrownBy(() -> service(2).ask(PUBLIC_ID, USER_ID, "T"))
                .isInstanceOf(InterviewAiUnavailableException.class)
                .hasCause(failure);

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("AI 가 이상한 응답을 주면 원인을 담은 InvalidInterviewResponseException 이고 저장 트랜잭션은 열지 않는다")
    void invalidResponseFailureBecomesInvalidResponse() {

        AgentUnavailableException failure = new AgentUnavailableException("이상함", false, true, null);
        when(port.nextQuestion(request)).thenThrow(failure);

        assertThatThrownBy(() -> service(2).ask(PUBLIC_ID, USER_ID, "T"))
                .isInstanceOf(InvalidInterviewResponseException.class)
                .hasCause(failure);

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableResults")
    @DisplayName("질문을 더 만들 수 있는데 COMPLETE 이거나 요청하지 않은 칸의 질문이면 상한 409 가 아니라 InvalidInterviewResponseException 이다")
    void unusableResultBecomesInvalidResponse(InterviewQuestionResult result) {

        when(port.nextQuestion(request)).thenReturn(result);

        assertThatThrownBy(() -> service(2).ask(PUBLIC_ID, USER_ID, "T"))
                .isInstanceOf(InvalidInterviewResponseException.class);

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    static Stream<Arguments> unusableResults() {

        return Stream.of(
                arguments(Named.of("COMPLETE", new InterviewQuestionResult(InterviewNextAction.COMPLETE, null, null,
                        null, null))),
                arguments(Named.of("질문 칸이 차 있는 COMPLETE", new InterviewQuestionResult(
                        InterviewNextAction.COMPLETE, InterviewStarSlot.T, 1, InterviewQuestionType.RECALL_AID, "글"))),
                arguments(Named.of("다른 칸의 질문", question(InterviewStarSlot.A))));

    }

    private static InterviewQuestionResult question(InterviewStarSlot slot) {

        return new InterviewQuestionResult(InterviewNextAction.ASK_AGAIN, slot, 1, InterviewQuestionType.RECALL_AID,
                "묻는 글");

    }

    private static InterviewTurn answered(int seq) {

        return InterviewTurnSamples.turn(seq, StarField.S, QuestionType.EVIDENCE_GAP, TriggerSource.USER, "질문",
                TurnOutcome.ANSWERED, "답", AnswerSource.TYPED);

    }

    private InterviewAskService service(int maxTurns) {

        return new InterviewAskService(ownership, generation, turns, candidates, assembler, port, new InterviewLimits(maxTurns),
                transactionManager, mock(UserRateLimiter.class));

    }
}
