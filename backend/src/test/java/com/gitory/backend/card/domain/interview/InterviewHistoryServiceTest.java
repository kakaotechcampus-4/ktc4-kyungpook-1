package com.gitory.backend.card.domain.interview;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.CardOwnershipService;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewHistoryServiceTest {

    private static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Long USER_ID = 7L;
    private static final Long CARD_ID = 5L;

    private final CardOwnershipService ownership = mock(CardOwnershipService.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);

    @BeforeEach
    void setUp() {

        Card card = Card.startManual(USER_ID, 11L, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(ownership.findOwned(PUBLIC_ID, USER_ID, AuditAction.VIEW_CARD)).thenReturn(card);

    }

    @Test
    @DisplayName("내 카드를 VIEW_CARD 로 확인하고, 그 카드의 질문만 읽은 순서 그대로 질문 번호·직접 작성 출처로 돌려준다")
    void listsTurnsOfOwnedCardInReadOrder() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID))
                .thenReturn(List.of(InterviewTurnSamples.open(1), InterviewTurnSamples.open(2)));

        List<InterviewTurnView> history = service(2).history(PUBLIC_ID, USER_ID);

        assertThat(history).extracting(InterviewTurnView::turnNo).containsExactly(1, 2);
        assertThat(history).extracting(InterviewTurnView::sourceType)
                .containsOnly(InterviewSourceType.DIRECT_CARD);
        verify(ownership).findOwned(PUBLIC_ID, USER_ID, AuditAction.VIEW_CARD);

    }

    @Test
    @DisplayName("질문이 없는 카드는 빈 목록이다")
    void returnsEmptyListWithoutTurns() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of());

        assertThat(service(2).history(PUBLIC_ID, USER_ID)).isEmpty();

    }

    @Test
    @DisplayName("최대 질문 수는 설정 값을 따라 3 이면 1번 질문에 남은 질문이 2개이고 2번에 1개다")
    void followsConfiguredMaxTurns() {

        when(turns.findByCardIdOrderBySeqAsc(CARD_ID))
                .thenReturn(List.of(InterviewTurnSamples.open(1), InterviewTurnSamples.open(2)));

        List<InterviewTurnView> history = service(3).history(PUBLIC_ID, USER_ID);

        assertThat(history).extracting(InterviewTurnView::maxTurns).containsExactly(3, 3);
        assertThat(history).extracting(InterviewTurnView::remaining).containsExactly(2, 1);

    }

    @Test
    @DisplayName("내 카드가 아니면 CardNotFoundException 이 그대로 나가고 질문은 읽지 않는다")
    void doesNotReadTurnsOfCardNotOwned() {

        UUID othersCard = UUID.fromString("22222222-2222-4222-8222-222222222222");
        when(ownership.findOwned(othersCard, USER_ID, AuditAction.VIEW_CARD)).thenThrow(new CardNotFoundException());

        assertThatThrownBy(() -> service(2).history(othersCard, USER_ID)).isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(turns);

    }

    private InterviewHistoryService service(int maxTurns) {

        return new InterviewHistoryService(ownership, turns, new InterviewLimits(maxTurns));

    }
}
