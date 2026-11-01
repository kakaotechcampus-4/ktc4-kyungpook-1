package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.card.infra.CardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ConfirmedCardChangesTest {

    private static final Long USER = 1L;
    private static final Long REPO = 7L;

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ConfirmedCardChanges changes = mock(ConfirmedCardChanges.class);
    private final CardThemeSuggester suggester = new CardThemeSuggester(mock(CardRepository.class),
            mock(CardVersionReader.class), mock(CardThemePort.class), events, mock(PlatformTransactionManager.class),
            changes);

    @Test
    @DisplayName("레포 카드가 이번에 확정되거나, 확정 카드의 글이 바뀌거나, 확정에서 작성 중으로 돌아가면 알린다")
    void announcesWhenTheCardIsOrWasConfirmed() {

        Card becomes = Card.startManual(USER, REPO, null, "결제 API", null, "a");
        suggester.settle(becomes, slots(true, true));

        Card edited = confirmed();
        suggester.settle(edited, slots(true, true));

        Card reopened = confirmed();
        suggester.settle(reopened, slots(false, true));

        verify(changes).announce(becomes);
        verify(changes).announce(edited);
        verify(changes).announce(reopened);

    }

    @Test
    @DisplayName("작성 중으로 남는 카드는 알리지 않는다")
    void doesNotAnnounceDraftThatStaysDraft() {

        suggester.settle(Card.startManual(USER, REPO, null, "결제 API", null, "a"), slots(true, false));

        verify(changes, never()).announce(any());

    }

    @Test
    @DisplayName("초안 Job 안에서 확정된 카드는 Job 이 끝날 때 한 번에 다시 계산하므로 카드마다 알리지 않는다")
    void draftJobDoesNotAnnounceEachCard() {

        suggester.settleInDraftJob(Card.startManual(USER, REPO, null, "결제 API", null, "a"), slots(true, true));

        verify(changes, never()).announce(any());

    }

    @Test
    @DisplayName("레포에 속하지 않은 카드는 레포 추천과 상관없어 알리지 않는다")
    void doesNotPublishForCardWithoutRepository() {

        new ConfirmedCardChanges(events).announce(Card.startManual(USER, null, 3L, "동아리 회계", null, "a"));
        new ConfirmedCardChanges(events).announce(Card.startManual(USER, REPO, null, "결제 API", null, "b"));

        verify(events).publishEvent(new ConfirmedCardChanged(USER, REPO));
        verify(events, never()).publishEvent(new ConfirmedCardChanged(USER, null));

    }

    private static Card confirmed() {

        Card card = Card.startManual(USER, REPO, null, "결제 API", null, "a");
        card.confirm();
        return card;

    }

    // 상황·행동이 차 있으면 확정할 수 있고, 네 칸이 다 차면 작성 중 카드가 확정된다
    private static VersionSlots slots(boolean situationAndAction, boolean taskAndResult) {

        Map<StarField, SlotContent> slots = new EnumMap<>(StarField.class);
        if (situationAndAction) {
            slots.put(StarField.S, slot(StarField.S));
            slots.put(StarField.A, slot(StarField.A));
        }
        if (taskAndResult) {
            slots.put(StarField.T, slot(StarField.T));
            slots.put(StarField.R, slot(StarField.R));
        }
        return new VersionSlots((short) 1, slots);

    }

    private static SlotContent slot(StarField field) {

        return new SlotContent(field, "글", EvidenceType.USER_STATED, Confidence.HIGH, null, List.of());

    }
}
