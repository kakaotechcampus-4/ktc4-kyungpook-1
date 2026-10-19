package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewAnswerOutcome;
import com.gitory.backend.agent.port.InterviewAnswerReviewPort;
import com.gitory.backend.agent.port.InterviewAnswerReviewRequest;
import com.gitory.backend.agent.port.InterviewAnswerReviewResult;
import com.gitory.backend.agent.port.InterviewAnswerSource;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewFollowUp;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.agent.port.InterviewStatementTarget;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 답을 저장한 뒤 평가가 모자람·충분함·실패일 때 다시 묻는 질문이 생기는지 협력 객체를 대신 세워 확인한다 */
class InterviewAnswerReviewServiceTest {

    private static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Long USER_ID = 7L;
    private static final Long CARD_ID = 5L;

    private final CardOwnershipService ownership = mock(CardOwnershipService.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final InterviewContextAssembler assembler = mock(InterviewContextAssembler.class);
    private final InterviewAnswerReviewPort port = mock(InterviewAnswerReviewPort.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final List<TransactionStatus> statuses = new ArrayList<>();

    private Card card;
    private InterviewTurn answered;

    @BeforeEach
    void setUp() {

        card = Card.startManual(USER_ID, 11L, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(ownership.findOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD)).thenReturn(card);
        when(ownership.lockOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD)).thenReturn(card);

        answered = InterviewTurnSamples.turn(1, StarField.A, QuestionType.EVIDENCE_GAP, TriggerSource.USER, "무엇을 했나요?",
                TurnOutcome.ANSWERED, "고쳤다", AnswerSource.TYPED);
        ReflectionTestUtils.setField(answered, "id", 9L);
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(answered));
        when(turns.save(any(InterviewTurn.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(assembler.assemble(any(Card.class), any(StarField.class), anyInt())).thenReturn(new InterviewQuestionRequest(
                CARD_ID, null, List.of(new InterviewMissingSlot(InterviewStarSlot.A, 1, "고쳤다", InterviewConfidence.HIGH,
                List.of())), 1, 2, InterviewCardSourceType.DIRECT_CARD));
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            TransactionStatus status = new SimpleTransactionStatus();
            statuses.add(status);
            return status;
        });

    }

    @Test
    @DisplayName("답이 모자라다는 평가를 받으면 같은 칸에 AI 가 만든 질문을 후속 질문으로 저장한다")
    void asksSameSlotAgainWhenAnswerIsShort() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        ArgumentCaptor<InterviewTurn> saved = ArgumentCaptor.forClass(InterviewTurn.class);
        verify(turns).save(saved.capture());
        InterviewTurn turn = saved.getValue();
        assertThat(turn.getSeq()).isEqualTo((short) 2);
        assertThat(turn.getStarSlot()).isEqualTo(StarField.A);
        assertThat(turn.getQuestionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(turn.getTriggerSource()).isEqualTo(TriggerSource.AUTO);
        assertThat(turn.getParentTurnId()).isEqualTo(9L);
        assertThat(turn.getQuestionText()).isEqualTo("어떤 방법으로 고쳤나요?");

    }

    @Test
    @DisplayName("답이 충분하다는 평가를 받으면 질문이 딸려 와도 만들지 않는다")
    void doesNotAskWhenAnswerIsEnough() {

        when(port.review(any(InterviewAnswerReviewRequest.class)))
                .thenReturn(new InterviewAnswerReviewResult(InterviewAnswerOutcome.ANSWERED, insufficient(InterviewStarSlot.A).followUp()));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("질문이 상한에 닿았으면 AI 에 묻지도 않고 다시 묻지 않는다")
    void doesNotAskAgainAtTheCap() {

        service(true, 1).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verifyNoInteractions(port);
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("평가가 실패하거나 시간이 지나도 예외 없이 끝나고 질문을 만들지 않는다")
    void survivesReviewFailure() {

        when(port.review(any(InterviewAnswerReviewRequest.class)))
                .thenThrow(new AgentUnavailableException("시간 초과", true, false, null));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 AI 를 부르지 않고 카드도 읽지 않는다")
    void doesNothingWhenSwitchedOff() {

        service(false, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verifyNoInteractions(port, ownership, turns);

    }

    @Test
    @DisplayName("AI 에는 질문을 포함한 질문 수, 저장된 답, 답한 질문, 상한을 그대로 보낸다")
    void sendsTheStoredAnswerAndCounts() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        ArgumentCaptor<InterviewAnswerReviewRequest> sent = ArgumentCaptor.forClass(InterviewAnswerReviewRequest.class);
        verify(port).review(sent.capture());
        InterviewAnswerReviewRequest request = sent.getValue();
        assertThat(request.cardId()).isEqualTo(CARD_ID);
        assertThat(request.existingTurnCount()).isEqualTo(1);
        assertThat(request.maxTurns()).isEqualTo(2);
        assertThat(request.answerText()).isEqualTo("고쳤다");
        assertThat(request.answerSource()).isEqualTo(InterviewAnswerSource.TYPED);
        assertThat(request.currentTurn().questionText()).isEqualTo("무엇을 했나요?");
        assertThat(request.currentTurn().questionType()).isEqualTo(InterviewQuestionType.EVIDENCE_GAP);
        assertThat(request.currentTurn().target()).isEqualTo(new InterviewStatementTarget(InterviewStarSlot.A, 1));
        assertThat(request.otherMissingSlots()).isEmpty();

    }

    @Test
    @DisplayName("고른 답으로 저장된 질문이면 AI 에도 고른 답(SELECTED)으로 보낸다")
    void sendsSelectedAnswerSource() {

        ReflectionTestUtils.setField(answered, "answerSource", AnswerSource.SELECTED);
        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        ArgumentCaptor<InterviewAnswerReviewRequest> sent = ArgumentCaptor.forClass(InterviewAnswerReviewRequest.class);
        verify(port).review(sent.capture());
        assertThat(sent.getValue().answerSource()).isEqualTo(InterviewAnswerSource.SELECTED);

    }

    @Test
    @DisplayName("답이 모자라도 후속 질문의 칸이 방금 답한 칸과 다르면 만들지 않는다")
    void doesNotAskWhenFollowUpTargetsAnotherSlot() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.R));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("확정된 카드면 AI 를 부르지 않는다")
    void doesNotCallAiForConfirmedCard() {

        ReflectionTestUtils.setField(card, "status", CardStatus.CONFIRMED);

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verifyNoInteractions(port);
        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("답한 질문이 카드의 마지막 질문이 아니면 AI 를 부르지 않는다")
    void doesNotCallAiWhenAnsweredTurnIsNotTheLast() {

        InterviewTurn later = InterviewTurnSamples.turn(2, StarField.R, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "결과는 어땠나요?", TurnOutcome.ANSWERED, "빨라졌다", AnswerSource.TYPED);
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(answered, later));

        service(true, 3).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verifyNoInteractions(port);

    }

    @Test
    @DisplayName("답한 질문이 이미 후속 질문(AUTO)이면 상한이 남아 있어도 평가하지 않는다")
    void doesNotReviewAnswerToAFollowUp() {

        InterviewTurn followUp = InterviewTurnSamples.turn(2, StarField.A, QuestionType.FOLLOWUP, TriggerSource.AUTO,
                "어떤 방법으로 고쳤나요?", TurnOutcome.ANSWERED, "인덱스를 추가했다", AnswerSource.TYPED);
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(answered, followUp));

        service(true, 3).askAgainIfShort(PUBLIC_ID, USER_ID, 2);

        verifyNoInteractions(port);

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 다른 질문이 저장됐으면 후속 질문을 만들지 않는다")
    void doesNotAskWhenAnotherTurnWasSavedWhileWaiting() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(answered))
                .thenReturn(List.of(answered, InterviewTurnSamples.open(2)));

        service(true, 3).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("AI 실패가 아닌 어떤 런타임 예외가 나도 예외를 밖으로 내지 않고 질문을 만들지 않는다")
    void swallowsAnyRuntimeException() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenThrow(new IllegalStateException("예상 못 한 실패"));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns, never()).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("저장이 UNIQUE(card_id, seq) 에 막혀도 예외를 밖으로 내지 않는다")
    void swallowsUniqueViolationOnSave() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));
        when(turns.save(any(InterviewTurn.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        verify(turns).save(any(InterviewTurn.class));

    }

    @Test
    @DisplayName("읽기는 REPEATABLE READ 읽기 전용으로 열었다 닫고, AI 를 부른 뒤에 기본 격리의 쓰기 트랜잭션을 따로 연다")
    void callsAiOutsideAnyTransaction() {

        when(port.review(any(InterviewAnswerReviewRequest.class))).thenReturn(insufficient(InterviewStarSlot.A));

        service(true, 2).askAgainIfShort(PUBLIC_ID, USER_ID, 1);

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(transactionManager, ownership, assembler, port, turns);
        order.verify(transactionManager).getTransaction(definitions.capture());
        order.verify(ownership).findOwned(PUBLIC_ID, USER_ID, AuditAction.EDIT_CARD);
        order.verify(assembler).assemble(any(Card.class), any(StarField.class), anyInt());
        order.verify(transactionManager).commit(statuses.get(0));
        order.verify(port).review(any(InterviewAnswerReviewRequest.class));
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

    private static InterviewAnswerReviewResult insufficient(InterviewStarSlot slot) {

        return new InterviewAnswerReviewResult(InterviewAnswerOutcome.INSUFFICIENT, new InterviewFollowUp(
                new InterviewStatementTarget(slot, 1), InterviewQuestionType.FOLLOWUP, "어떤 방법으로 고쳤나요?"));

    }

    private InterviewAnswerReviewService service(boolean enabled, int maxTurns) {

        return new InterviewAnswerReviewService(new InterviewAnswerReviewProperties(enabled), ownership, turns, assembler,
                port, new InterviewLimits(maxTurns), transactionManager);

    }
}
