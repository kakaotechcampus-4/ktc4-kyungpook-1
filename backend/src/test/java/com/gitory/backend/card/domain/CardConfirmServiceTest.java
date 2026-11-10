package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardConfirmServiceTest {

    private static final Long USER = 1L;
    private static final Long CARD_ID = 5L;

    private final CardOwnershipService ownership = mock(CardOwnershipService.class);
    private final CardVersionRepository versions = mock(CardVersionRepository.class);
    private final CardVersionReader reader = mock(CardVersionReader.class);
    private final CardVersionWriter writer = mock(CardVersionWriter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CardConfirmService service = new CardConfirmService(ownership, versions, reader, writer,
            mock(RepositoryReferenceService.class), mock(CandidateReferenceService.class), mock(AuditLog.class),
            mock(CardThemeSuggester.class), mock(ConfirmedCardChanges.class), events);

    @Test
    @DisplayName("유형이 이미 있는 카드를 확정하면 자소서가 새 카드를 볼 수 있게 CardThemesReady 를 알린다")
    void announcesThemesReadyWhenConfirmedWithThemes() {

        Card card = draftCard();
        card.chooseThemes(List.of(CardTheme.PROBLEM_SOLVING));

        confirm(card);

        verify(events).publishEvent(new CardThemesReady(CARD_ID, USER));

    }

    @Test
    @DisplayName("유형이 비어 있는 카드는 확정해도 알리지 않는다 - 유형이 붙을 때 알린다")
    void doesNotAnnounceWithoutThemes() {

        confirm(draftCard());

        verify(events, never()).publishEvent(any(CardThemesReady.class));

    }

    private Card draftCard() {

        Card card = Card.startManual(USER, null, null, "결제 장애 대응", null, "a");
        ReflectionTestUtils.setField(card, "id", CARD_ID);

        return card;

    }

    private void confirm(Card card) {

        VersionSlots slots = mock(VersionSlots.class);
        when(slots.isConfirmable()).thenReturn(true);
        CardVersion version = mock(CardVersion.class);
        UUID publicId = card.getPublicId();
        when(ownership.lockOwned(publicId, USER, AuditAction.CONFIRM_CARD)).thenReturn(card);
        when(reader.read(anyLong(), anyShort())).thenReturn(slots);
        when(versions.findByCardIdAndVersionNo(anyLong(), anyShort())).thenReturn(Optional.of(version));
        when(writer.copyToNext(any(), anyShort(), any())).thenReturn(version);

        service.confirm(publicId, USER);

    }
}
