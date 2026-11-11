package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.agent.port.ExperienceTheme;
import com.gitory.backend.card.infra.CardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardThemeSuggesterTest {

    private static final Long USER = 1L;
    private static final Long CARD_ID = 5L;

    private final CardRepository cards = mock(CardRepository.class);
    private final CardVersionReader reader = mock(CardVersionReader.class);
    private final CardThemePort themes = mock(CardThemePort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CardThemeSuggester suggester = new CardThemeSuggester(cards, reader, themes, events,
            mock(PlatformTransactionManager.class), mock(ConfirmedCardChanges.class));

    @Test
    @DisplayName("AI 가 고른 유형을 카드에 저장한 뒤에 CardThemesReady 를 알린다")
    void announcesAfterThemesAreSaved() {

        Card card = needingThemes();
        when(themes.themesFor(any())).thenReturn(List.of(ExperienceTheme.CHALLENGE));

        suggester.suggest(new CardThemesNeeded(CARD_ID));

        assertThat(card.themeTags()).containsExactly(CardTheme.CHALLENGE);
        verify(events).publishEvent(new CardThemesReady(CARD_ID, USER));

    }

    @Test
    @DisplayName("AI 가 아무 유형도 고르지 못하면 알리지 않는다")
    void doesNotAnnounceWhenNoThemeWasChosen() {

        needingThemes();
        when(themes.themesFor(any())).thenReturn(List.of());

        suggester.suggest(new CardThemesNeeded(CARD_ID));

        verify(events, never()).publishEvent(any(CardThemesReady.class));

    }

    private Card needingThemes() {

        Card card = Card.startManual(USER, null, null, "결제 장애 대응", null, "a");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(cards.findById(anyLong())).thenReturn(Optional.of(card));
        when(cards.findWithLockById(anyLong())).thenReturn(Optional.of(card));
        when(reader.read(anyLong(), anyShort())).thenReturn(new VersionSlots((short) 1, Map.of()));

        return card;

    }
}
