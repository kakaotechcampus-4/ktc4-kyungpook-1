package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewAutoAskerTest {

    private static final Long USER_ID = 7L;
    private static final Long CARD_ID = 5L;

    private final CardRepository cards = mock(CardRepository.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final InterviewAskService askService = mock(InterviewAskService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private Card card;

    @BeforeEach
    void setUp() {

        card = Card.startManual(USER_ID, 11L, null, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(cards.findById(CARD_ID)).thenReturn(Optional.of(card));
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of());
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());

    }

    @Test
    @DisplayName("초안이 저장된 카드가 아직 초안이고 열린 질문이 없으면 그 카드의 첫 질문을 서버가 만든다")
    void asksFirstQuestionOfDraftCard() {

        asker(8).ask(new InterviewQuestionNeeded(CARD_ID));

        verify(askService).askFirst(card.getPublicId(), USER_ID);

    }

    @Test
    @DisplayName("커밋 사이에 확정된 카드에는 질문을 만들지 않고 AI 도 부르지 않는다")
    void skipsConfirmedCard() {

        ReflectionTestUtils.setField(card, "status", CardStatus.CONFIRMED);

        asker(8).ask(new InterviewQuestionNeeded(CARD_ID));

        verifyNoInteractions(askService);

    }

    @Test
    @DisplayName("답을 기다리는 질문이 이미 있으면 두 번째 질문을 만들지 않는다")
    void skipsCardWithOpenTurn() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(InterviewTurnSamples.open(1)));

        asker(8).ask(new InterviewQuestionNeeded(CARD_ID));

        verifyNoInteractions(askService);

    }

    @Test
    @DisplayName("질문 상한에 닿은 카드에는 질문을 만들지 않는다")
    void skipsCardAtTurnCap() {

        InterviewTurn skipped = InterviewTurnSamples.open(1);
        skipped.skip();
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(skipped));

        asker(1).ask(new InterviewQuestionNeeded(CARD_ID));

        verifyNoInteractions(askService);

    }

    @Test
    @DisplayName("AI 가 질문을 만들지 못해 예외가 나도 밖으로 던지지 않는다")
    void swallowsAiFailure() {

        when(askService.askFirst(any(), any())).thenThrow(new InterviewAiUnavailableException(null));

        assertThatCode(() -> asker(8).ask(new InterviewQuestionNeeded(CARD_ID))).doesNotThrowAnyException();

    }

    private InterviewAutoAsker asker(int maxTurns) {

        return new InterviewAutoAsker(cards, turns, new InterviewLimits(maxTurns), askService, transactionManager);

    }
}
