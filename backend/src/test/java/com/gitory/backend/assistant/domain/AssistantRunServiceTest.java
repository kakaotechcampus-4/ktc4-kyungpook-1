package com.gitory.backend.assistant.domain;

import com.gitory.backend.assistant.infra.AssistantRunRepository;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardLabel;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.domain.CoverLetterQueryService;
import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterSummary;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.ingest.domain.RepositoryListService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssistantRunServiceTest {

    private static final Long USER = 1L;

    private final AssistantRunRepository runs = mock(AssistantRunRepository.class);
    private final RepositoryListService repositories = mock(RepositoryListService.class);
    private final MatchingCardReader matchingCards = mock(MatchingCardReader.class);
    private final CoverLetterQueryService coverLetters = mock(CoverLetterQueryService.class);
    private final AssistantRunService service = new AssistantRunService(runs, new AssistantProperties(6),
            repositories, matchingCards, coverLetters, mock(UserRateLimiter.class), mock(AuditLog.class));

    @Test
    @DisplayName("실행을 만들 때 빈 칸이 있는 확정 카드와 초안이 끝난 자소서에만 최근 순서로 c1, l1 이름표를 붙여 고정한다")
    void fixesCardAndLetterLabelsOnlyForBlankCardsAndDraftedLetters() {

        MatchingCard full = card(1L, "다 채운 카드", "상황", "과제", "행동", "결과");
        MatchingCard blankResult = card(2L, "결과가 빈 카드", "상황", "과제", "행동", null);
        MatchingCard blankAll = card(3L, "다 빈 카드", null, null, null, null);
        given(matchingCards.confirmedOf(USER)).willReturn(List.of(full, blankResult, blankAll));
        given(matchingCards.labelsOf(any())).willReturn(Map.of(
                1L, label("다 채운 카드"), 2L, label("결과가 빈 카드"), 3L, label("다 빈 카드")));
        given(coverLetters.list(USER)).willReturn(List.of(
                summary(CoverLetterStage.ASKING, "묻는 중", null, 2),
                summary(CoverLetterStage.DRAFTED, "백엔드 개발자", "토스", 3),
                summary(CoverLetterStage.DRAFTED, "서버 개발자", null, 1)));
        given(runs.save(any(AssistantRun.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(repositories.saved(any(), any())).willReturn(List.of());

        service.start(USER, "grow22", "카드 빈 칸 채워 줘");

        ArgumentCaptor<AssistantRun> saved = ArgumentCaptor.forClass(AssistantRun.class);
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getCards())
                .extracting(CardLabel::ref, CardLabel::title, CardLabel::missing)
                .containsExactly(tuple("c1", "다 빈 카드", List.of(StarField.S, StarField.T, StarField.A, StarField.R)),
                        tuple("c2", "결과가 빈 카드", List.of(StarField.R)));
        assertThat(saved.getValue().getLetters())
                .extracting(LetterLabel::ref, LetterLabel::jobRole, LetterLabel::company, LetterLabel::questionCount)
                .containsExactly(tuple("l1", "백엔드 개발자", "토스", 3), tuple("l2", "서버 개발자", null, 1));

    }

    @Test
    @DisplayName("AI 에 보낼 카드 이름표는 가리기 전 제목이 아니라 가리기 규칙을 거친 제목을 쓴다")
    void cardLabelUsesMaskedTitle() {

        MatchingCard masked = card(1L, "[회사] 결제 장애 대응", null, "과제", "행동", "결과");
        MatchingCardLabel original = label("토스 결제 장애 대응");
        given(matchingCards.confirmedOf(USER)).willReturn(List.of(masked));
        given(matchingCards.labelsOf(any())).willReturn(Map.of(1L, original));
        given(coverLetters.list(USER)).willReturn(List.of());
        given(runs.save(any(AssistantRun.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(repositories.saved(any(), any())).willReturn(List.of());

        service.start(USER, "grow22", "카드 빈 칸 채워 줘");

        ArgumentCaptor<AssistantRun> saved = ArgumentCaptor.forClass(AssistantRun.class);
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getCards())
                .extracting(CardLabel::cardId, CardLabel::title)
                .containsExactly(tuple(original.publicId(), "[회사] 결제 장애 대응"));

    }

    @Test
    @DisplayName("카드와 자소서 이름표는 각각 10개까지만 붙인다")
    void labelsAtMostTenCardsAndLetters() {

        List<MatchingCard> cards = IntStream.rangeClosed(1, 12)
                .mapToObj(id -> card((long) id, "카드 " + id, null, null, null, null)).toList();
        given(matchingCards.confirmedOf(USER)).willReturn(cards);
        given(matchingCards.labelsOf(any())).willAnswer(invocation -> {
            Map<Long, MatchingCardLabel> labels = new HashMap<>();
            for (MatchingCard card : cards) {
                labels.put(card.id(), label(card.title()));
            }
            return labels;
        });
        List<CoverLetterSummary> letters = new ArrayList<>();
        IntStream.rangeClosed(1, 12)
                .forEach(index -> letters.add(summary(CoverLetterStage.DRAFTED, "직무 " + index, null, 1)));
        given(coverLetters.list(USER)).willReturn(letters);
        given(runs.save(any(AssistantRun.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(repositories.saved(any(), any())).willReturn(List.of());

        service.start(USER, "grow22", "카드 빈 칸 채워 줘");

        ArgumentCaptor<AssistantRun> saved = ArgumentCaptor.forClass(AssistantRun.class);
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getCards()).hasSize(10).extracting(CardLabel::title)
                .startsWith("카드 12", "카드 11").endsWith("카드 3");
        assertThat(saved.getValue().getLetters()).hasSize(10);

    }

    private static MatchingCard card(Long id, String title, String situation, String task, String action,
                                     String result) {

        return new MatchingCard(id, null, title, situation, task, action, result, List.of());

    }

    private static MatchingCardLabel label(String title) {

        return new MatchingCardLabel(UUID.randomUUID(), title, CardKind.QUALITATIVE, null);

    }

    private static CoverLetterSummary summary(CoverLetterStage stage, String jobRole, String company,
                                              int questionCount) {

        CoverLetterView.PostingRef posting = company == null ? null
                : new CoverLetterView.PostingRef(UUID.randomUUID(), company, "공고");

        return new CoverLetterSummary(UUID.randomUUID(), stage, jobRole, posting, questionCount, 0, "첫 문항",
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

    }
}
