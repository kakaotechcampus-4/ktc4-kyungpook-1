package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewCommit;
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
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.DraftCommit;
import com.gitory.backend.recommend.domain.CandidateDraftSource;
import com.gitory.backend.recommend.domain.CandidateDraftSourceService;
import com.gitory.backend.recommend.domain.CandidateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewContextAssemblerTest {

    private static final Long CARD_ID = 5L;
    private static final short CURRENT_VERSION = 3;

    private final CardVersionReader reader = mock(CardVersionReader.class);
    private final CandidateDraftSourceService draftSources = mock(CandidateDraftSourceService.class);
    private final CommitSummaryService commitSummaries = mock(CommitSummaryService.class);
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

        InterviewQuestionRequest request = assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(1));

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

        assertThat(assembler(3).assemble(card, List.of(StarField.T), StarField.T, asked(0)).maxTurns()).isEqualTo(3);

    }

    @Test
    @DisplayName("카드의 현재 버전 번호로 글을 읽는다")
    void readsTheCurrentVersion() {

        assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0));

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

        List<InterviewMissingSlot> sent = assembler(2).assemble(card, List.of(requested), requested, asked(0)).missingSlots();

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
    @DisplayName("확신도가 비어 있는 커밋 근거 칸은 LOW 로 보낸다")
    void sendsLowForCommitSlotWithoutConfidence() {

        slots(Map.of(StarField.T, slot(StarField.T, "글", EvidenceType.COMMIT, null, List.of(31L, 32L))));

        assertThat(confidenceSent(StarField.T)).isEqualTo(InterviewConfidence.LOW);

    }

    @Test
    @DisplayName("직접 추가한 후보로 만든 카드는 후보 null 과 DIRECT_CARD 로 보내고 커밋 요약은 조회하지 않는다")
    void sendsNullCandidateForManualCandidate() {

        cardOfCandidate(9L, source(CandidateType.MANUAL, null));

        InterviewQuestionRequest request = assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0));

        assertThat(request.candidate()).isNull();
        assertThat(request.sourceType()).isEqualTo(InterviewCardSourceType.DIRECT_CARD);
        verifyNoInteractions(commitSummaries);

    }

    @Test
    @DisplayName("후보 커밋은 초안과 같은 조회에 50개를 물어 받은 순서 그대로 보낸다")
    void sendsCandidateCommitsInTheOrderOfTheDraftQuery() {

        cardOfCandidate(9L, source(CandidateType.PR, 7, draft(2L, "b"), draft(1L, "a")));
        summaries(summary(1L, "a", "첫째"), summary(2L, "b", "둘째"));

        InterviewQuestionRequest request = assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0));

        assertThat(request.candidate().commits()).containsExactly(new InterviewCommit(2L, "b", "둘째"),
                new InterviewCommit(1L, "a", "첫째"));
        verify(draftSources).load(9L, 50);

    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"\n  feat: 인덱스 추가  \n\n이유 본문\n\nCo-Authored-By: a <a@b>", "feat: 인덱스 추가\r본문",
            "feat: 인덱스 추가\u2028본문", "feat: 인덱스 추가"})
    @DisplayName("메시지는 빈 글이 아닌 첫 줄만 앞뒤 공백을 지워 보낸다")
    void sendsOnlyTheFirstNonBlankLineOfTheMessage(String message) {

        cardOfCandidate(9L, source(CandidateType.PR, 7, draft(1L, "a")));
        summaries(summary(1L, "a", message));

        assertThat(assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0)).candidate().commits())
                .containsExactly(new InterviewCommit(1L, "a", "feat: 인덱스 추가"));

    }

    @ParameterizedTest(name = "{0}자")
    @CsvSource({"200, 200, false", "201, 200, true"})
    @DisplayName("첫 줄이 200 코드 포인트를 넘을 때만 앞 200 코드 포인트와 줄임표를 보내고, 이모지를 반으로 가르지 않는다")
    void truncatesLongFirstLineByCodePoints(int length, int kept, boolean ellipsis) {

        cardOfCandidate(9L, source(CandidateType.PR, 7, draft(1L, "a")));
        summaries(summary(1L, "a", "😀".repeat(length)));

        assertThat(assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0)).candidate().commits().get(0).message())
                .isEqualTo("😀".repeat(kept) + (ellipsis ? "…" : ""));

    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {"", " \t\n", "\u00a0", "\u0085\u3000", "\ufeff"})
    @DisplayName("메시지가 없거나 공백·NBSP·U+0085·U+FEFF 만 있는 커밋은 후보 커밋과 근거 커밋에서 모두 뺀다")
    void dropsCommitsWithoutAMessageFromBothLists(String blank) {

        cardOfCandidate(9L, source(CandidateType.PR, 7, draft(1L, "a"), draft(2L, "b"), draft(3L, "c")));
        slots(Map.of(StarField.T, slot(StarField.T, "글", EvidenceType.COMMIT, null, List.of(1L, 3L))));
        summaries(summary(1L, "a", blank), summary(2L, "b", "남는 커밋"), summary(3L, "c", null));

        InterviewQuestionRequest request = assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0));

        assertThat(request.candidate().commits()).containsExactly(new InterviewCommit(2L, "b", "남는 커밋"));
        assertThat(request.missingSlots().get(0).linkedCommits()).isEmpty();

    }

    @Test
    @DisplayName("칸에 이어진 근거 커밋을 저장된 순서대로 linked_commits 로 보내고, 조회되지 않은 커밋은 뺀다")
    void sendsLinkedCommitsInStoredOrder() {

        slots(Map.of(StarField.T, slot(StarField.T, "글", EvidenceType.COMMIT, null, List.of(32L, 31L, 33L))));
        summaries(summary(31L, "x", "feat: 31"), summary(32L, "y", "feat: 32"));

        InterviewMissingSlot sent = assembler(2).assemble(card, List.of(StarField.T), StarField.T, asked(0)).missingSlots().get(0);

        assertThat(sent.linkedCommits()).containsExactly(new InterviewCommit(32L, "y", "feat: 32"),
                new InterviewCommit(31L, "x", "feat: 31"));

    }

    private InterviewConfidence confidenceSent(StarField field) {

        return assembler(2).assemble(card, List.of(field), field, asked(0)).missingSlots().get(0).confidence();

    }

    private void slots(Map<StarField, SlotContent> slots) {

        when(reader.read(CARD_ID, CURRENT_VERSION)).thenReturn(new VersionSlots(CURRENT_VERSION, slots));

    }

    private static SlotContent slot(StarField field, String body, EvidenceType evidenceType, Confidence confidence,
                                    List<Long> commitIds) {

        return new SlotContent(field, body, evidenceType, confidence, null, commitIds);

    }

    private void cardOfCandidate(Long candidateId, CandidateDraftSource source) {

        ReflectionTestUtils.setField(card, "candidateId", candidateId);
        when(draftSources.load(candidateId, 50)).thenReturn(Optional.of(source));

    }

    private static CandidateDraftSource source(CandidateType type, Integer prNumber, DraftCommit... commits) {

        return new CandidateDraftSource(UUID.randomUUID(), type, prNumber, null, List.of(commits));

    }

    private static DraftCommit draft(Long id, String sha) {

        return new DraftCommit(id, sha);

    }

    private static CommitSummary summary(Long id, String sha, String message) {

        return new CommitSummary(id, sha, message, null, null, null);

    }

    private void summaries(CommitSummary... summaries) {

        when(commitSummaries.findByIds(anyCollection())).thenReturn(Arrays.stream(summaries)
                .collect(Collectors.toMap(CommitSummary::id, summary -> summary)));

    }

    private InterviewContextAssembler assembler(int maxTurns) {

        return new InterviewContextAssembler(reader, draftSources, commitSummaries, new InterviewLimits(maxTurns));

    }

    // 질문 수만 세는 시험이라 결과가 없는 질문을 개수만큼 만든다
    private static List<InterviewTurn> asked(int count) {

        return IntStream.rangeClosed(1, count)
                .mapToObj(seq -> InterviewTurn.ask(1L, (short) seq, StarField.S, QuestionType.RECALL_AID, "질문"))
                .toList();

    }
}
