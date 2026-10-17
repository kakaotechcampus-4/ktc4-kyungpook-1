package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.card.domain.Card;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.Confidence;
import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.SlotContent;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.VersionSlots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewContextAssemblerTest {

    private static final Long CARD_ID = 5L;
    private static final short CURRENT_VERSION = 3;

    private final CardVersionReader reader = mock(CardVersionReader.class);
    private Card card;

    @BeforeEach
    void setUp() {

        card = Card.startManual(7L, 11L, "카드", null, "key-1");
        ReflectionTestUtils.setField(card, "id", CARD_ID);
        ReflectionTestUtils.setField(card, "currentVersion", CURRENT_VERSION);
        slots(Map.of());

    }

    @Test
    @DisplayName("빈 칸은 카드 번호·빈 후보·칸 하나(글 null·LOW·근거 커밋 없음)·받은 질문 수·설정의 상한·직접 작성 출처로 조립한다")
    void assemblesEmptySlot() {

        InterviewQuestionRequest request = assembler(2).assemble(card, StarField.T, 1);

        assertThat(request.cardId()).isEqualTo(CARD_ID);
        assertThat(request.candidate()).isNull();
        assertThat(request.existingTurnCount()).isEqualTo(1);
        assertThat(request.maxTurns()).isEqualTo(2);
        assertThat(request.sourceType()).isEqualTo(InterviewCardSourceType.DIRECT_CARD);
        assertThat(request.missingSlots()).containsExactly(
                new InterviewMissingSlot(InterviewStarSlot.T, 1, null, InterviewConfidence.LOW, List.of()));

    }

    @Test
    @DisplayName("상한은 설정 값을 따른다")
    void followsConfiguredMaxTurns() {

        assertThat(assembler(3).assemble(card, StarField.T, 0).maxTurns()).isEqualTo(3);

    }

    @Test
    @DisplayName("카드의 현재 버전 번호로 글을 읽는다")
    void readsTheCurrentVersion() {

        assembler(2).assemble(card, StarField.T, 0);

        verify(reader).read(CARD_ID, CURRENT_VERSION);

    }

    @ParameterizedTest
    @EnumSource(StarField.class)
    @DisplayName("다른 칸이 차 있어도 요청한 칸 하나만 조립하고, 글은 앞뒤 공백까지 저장된 그대로 쓴다")
    void assemblesOnlyTheRequestedSlot(StarField requested) {

        Map<StarField, SlotContent> filled = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            filled.put(field, slot(field, "  " + field + " 칸의 글  ", EvidenceType.USER_STATED, null, List.of()));
        }
        slots(filled);

        List<InterviewMissingSlot> sent = assembler(2).assemble(card, requested, 0).missingSlots();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).starSlot()).isEqualTo(InterviewStarSlot.valueOf(requested.name()));
        assertThat(sent.get(0).statementSeq()).isEqualTo(1);
        assertThat(sent.get(0).body()).isEqualTo("  " + requested + " 칸의 글  ");

    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EvidenceType.class, names = {"USER_STATED", "USER_SELECTED"})
    @DisplayName("사용자가 쓰거나 고른 칸은 확신도가 비어 있으면 HIGH 로 보낸다")
    void sendsUserWrittenSlotWithoutConfidenceAsHigh(EvidenceType evidenceType) {

        slots(Map.of(StarField.T, slot(StarField.T, "글", evidenceType, null, List.of())));

        assertThat(confidenceSent(StarField.T)).isEqualTo(InterviewConfidence.HIGH);

    }

    @ParameterizedTest(name = "{0} · {1}")
    @CsvSource({"COMMIT, LOW", "COMMIT, MEDIUM", "COMMIT, HIGH", "USER_STATED, LOW", "USER_STATED, MEDIUM",
            "USER_SELECTED, LOW"})
    @DisplayName("저장된 확신도는 근거 종류와 상관없이 그대로 보낸다")
    void sendsStoredConfidenceAsStored(EvidenceType evidenceType, Confidence confidence) {

        slots(Map.of(StarField.T, slot(StarField.T, "글", evidenceType, confidence, List.of())));

        assertThat(confidenceSent(StarField.T)).isEqualTo(InterviewConfidence.valueOf(confidence.name()));

    }

    @Test
    @DisplayName("확신도가 비어 있는 커밋 근거 칸은 LOW 이고, 근거 커밋이 있어도 linked_commits 는 빈 배열이다")
    void sendsLowAndNoLinkedCommitsForCommitSlotWithoutConfidence() {

        slots(Map.of(StarField.T, slot(StarField.T, "글", EvidenceType.COMMIT, null, List.of(31L, 32L))));

        InterviewMissingSlot sent = assembler(2).assemble(card, StarField.T, 0).missingSlots().get(0);

        assertThat(sent.confidence()).isEqualTo(InterviewConfidence.LOW);
        assertThat(sent.linkedCommits()).isEmpty();

    }

    @Test
    @DisplayName("출처 종류는 InterviewSourceType.of 한 곳에서 받은 값을 그대로 쓴다")
    void takesSourceTypeFromTheSingleMethod() {

        try (MockedStatic<InterviewSourceType> source = mockStatic(InterviewSourceType.class, CALLS_REAL_METHODS)) {
            source.when(() -> InterviewSourceType.of(card)).thenReturn(InterviewSourceType.COMMIT_CLUSTER);

            assertThat(assembler(2).assemble(card, StarField.T, 0).sourceType())
                    .isEqualTo(InterviewCardSourceType.COMMIT_CLUSTER);
        }

    }

    private InterviewConfidence confidenceSent(StarField field) {

        return assembler(2).assemble(card, field, 0).missingSlots().get(0).confidence();

    }

    private void slots(Map<StarField, SlotContent> slots) {

        when(reader.read(CARD_ID, CURRENT_VERSION)).thenReturn(new VersionSlots(CURRENT_VERSION, slots));

    }

    private static SlotContent slot(StarField field, String body, EvidenceType evidenceType, Confidence confidence,
                                    List<Long> commitIds) {

        return new SlotContent(field, body, evidenceType, confidence, null, commitIds);

    }

    private InterviewContextAssembler assembler(int maxTurns) {

        return new InterviewContextAssembler(reader, new InterviewLimits(maxTurns));

    }
}
