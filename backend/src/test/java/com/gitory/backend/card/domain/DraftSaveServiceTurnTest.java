package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.QuestionType;
import com.gitory.backend.card.domain.interview.TurnOutcome;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftSaveServiceTurnTest {

    private static final Long USER_ID = 7L;
    private static final Long CARD_ID = 5L;

    private final CardOwnershipService ownership = mock(CardOwnershipService.class);
    private final CardVersionRepository versions = mock(CardVersionRepository.class);
    private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    private final CardVersionReader reader = mock(CardVersionReader.class);
    private final DraftSaveService service = new DraftSaveService(ownership, versions,
            mock(CardStatementRepository.class), mock(CardSlotNoteRepository.class), reader,
            mock(CardVersionWriter.class), mock(CardThemeSuggester.class), turns);

    private final UUID publicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {

        Card card = Card.startManual(USER_ID, 11L, null, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        when(ownership.lockOwned(publicId, USER_ID, AuditAction.EDIT_CARD)).thenReturn(card);
        when(versions.findByCardIdAndVersionNo(CARD_ID, (short) 1))
                .thenReturn(Optional.of(CardVersion.of(CARD_ID, (short) 1, VersionSource.USER_EDIT)));
        when(reader.read(CARD_ID, (short) 1)).thenReturn(new VersionSlots((short) 1, Map.of()));

    }

    @Test
    @DisplayName("직접 저장한 칸을 묻던 열린 질문은 넘긴 것으로 닫고 다른 칸의 열린 질문은 그대로 둔다")
    void closesOpenQuestionOfFilledSlotOnly() {

        InterviewTurn asksT = InterviewTurn.ask(CARD_ID, (short) 1, StarField.T, QuestionType.EVIDENCE_GAP, "과제 질문");
        InterviewTurn asksS = InterviewTurn.ask(CARD_ID, (short) 2, StarField.S, QuestionType.EVIDENCE_GAP, "상황 질문");
        when(turns.findByCardIdOrderBySeqAsc(CARD_ID)).thenReturn(List.of(asksT, asksS));
        Map<StarField, String> texts = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            texts.put(field, field == StarField.T ? "직접 쓴 과제" : null);
        }

        service.save(publicId, USER_ID, texts);

        assertThat(asksT.getOutcome()).isEqualTo(TurnOutcome.SKIPPED);
        assertThat(asksS.isClosed()).isFalse();

    }
}
