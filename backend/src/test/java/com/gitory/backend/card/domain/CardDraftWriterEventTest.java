package com.gitory.backend.card.domain;

import com.gitory.backend.card.domain.interview.InterviewQuestionNeeded;
import com.gitory.backend.card.infra.CardDraftStepRepository;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.StatementEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardDraftWriterEventTest {

    private static final Long CARD_ID = 5L;

    private final CardRepository cards = mock(CardRepository.class);
    private final CardVersionRepository versions = mock(CardVersionRepository.class);
    private final CardStatementRepository statements = mock(CardStatementRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CardDraftWriter writer = new CardDraftWriter(cards, versions, statements,
            mock(StatementEvidenceRepository.class), mock(CardSlotNoteRepository.class),
            mock(CardDraftStepRepository.class), mock(CardVersionReader.class), mock(CardThemeSuggester.class), events);

    @BeforeEach
    void setUp() {

        Card card = Card.startManual(7L, 11L, null, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(cards.findWithLockById(CARD_ID)).thenReturn(Optional.of(card));
        when(versions.findByCardIdAndVersionNo(CARD_ID, (short) 1))
                .thenReturn(Optional.of(CardVersion.of(CARD_ID, (short) 1, VersionSource.USER_EDIT)));
        when(statements.findByCardIdAndVersionNo(CARD_ID, (short) 1)).thenReturn(List.of());

    }

    @Test
    @DisplayName("근거가 없어 빈 칸으로 남긴 초안 카드는 첫 질문을 서버가 만들도록 알린다")
    void noEvidenceDraftAsksFirstQuestion() {

        writer.writeNoEvidence(CARD_ID);

        verify(events).publishEvent(new InterviewQuestionNeeded(CARD_ID));

    }

    @Test
    @DisplayName("초안을 채우지 못해 실패로 표시한 카드는 첫 질문을 만들도록 알리지 않는다")
    void failedCardDoesNotAskQuestion() {

        writer.markFailed(CARD_ID, CardDraftFailure.INTERNAL_ERROR);

        verify(events, never()).publishEvent(any(Object.class));

    }
}
