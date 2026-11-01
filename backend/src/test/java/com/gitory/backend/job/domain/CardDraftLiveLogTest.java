package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.StarConfidence;
import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftListener;
import com.gitory.backend.agent.port.StarEmptyReason;
import com.gitory.backend.agent.port.StarMaterialKind;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.agent.port.StarStep;
import com.gitory.backend.agent.port.StarStepActor;
import com.gitory.backend.agent.port.StarStepKind;
import com.gitory.backend.card.domain.DraftMaterial;
import com.gitory.backend.card.domain.DraftStep;
import com.gitory.backend.card.domain.DraftStepActor;
import com.gitory.backend.card.domain.DraftStepKind;
import com.gitory.backend.card.domain.GeneratedCard;
import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CardDraftLiveLogTest {

    private static final Long JOB = 1L;
    private static final UUID FIRST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));

    private CardDraftLiveLog log;

    @BeforeEach
    void setUp() {

        Clock clock = mock(Clock.class);
        given(clock.instant()).willAnswer(invocation -> now.get());
        log = new CardDraftLiveLog(clock);
        log.start(JOB, List.of(new GeneratedCard(10L, FIRST_ID, 100L, "결제 중복 승인 막기"),
                new GeneratedCard(20L, SECOND_ID, 200L, "세션 조회 줄이기")));

    }

    @Test
    @DisplayName("카드는 만든 순서대로 WAITING 으로 시작해 차례가 오면 READING, AI 를 부르면 WRITING, 끝나면 DONE 이 되고 다음 카드는 그동안 WAITING 이다")
    void followsEachCardTurn() {

        log.read(JOB, 10L);
        assertThat(states()).containsExactly(CardDraftLiveState.READING, CardDraftLiveState.WAITING);

        log.write(JOB, 10L);
        assertThat(states()).containsExactly(CardDraftLiveState.WRITING, CardDraftLiveState.WAITING);

        log.end(JOB, 10L, CardDraftResult.DRAFTED);
        assertThat(log.of(JOB)).extracting(CardDraftLive::cardId, CardDraftLive::title, CardDraftLive::state)
                .containsExactly(tuple(FIRST_ID, "결제 중복 승인 막기", CardDraftLiveState.DONE),
                        tuple(SECOND_ID, "세션 조회 줄이기", CardDraftLiveState.WAITING));

    }

    @ParameterizedTest
    @CsvSource({"DRAFTED, DONE", "SKIPPED, DONE", "NO_EVIDENCE, DONE", "TIMED_OUT, DONE",
            "GITHUB_UNAUTHORIZED, FAILED", "AI_UNAVAILABLE, FAILED", "FAILED, FAILED"})
    @DisplayName("카드를 채우지 못한 결과만 FAILED 로 끝내고, 시간이 모자라 일부만 채웠거나 AI 를 부를 필요가 없던 카드는 DONE 이다")
    void endsOnlyUnfilledCardsAsFailed(CardDraftResult result, CardDraftLiveState expected) {

        log.end(JOB, 10L, result);

        assertThat(states().getFirst()).isEqualTo(expected);

    }

    @Test
    @DisplayName("칸마다 지금까지 쓴 전체 글 하나만 S·T·A·R 순서로 두고, 다시 쓰면 바퀴 번호와 글이 새 것으로 바뀐다")
    void keepsLatestTextPerSlot() {

        StarDraftListener listener = log.write(JOB, 10L);
        listener.slot(0, StarSlot.A, "주문마다", false);
        listener.slot(0, StarSlot.S, "결제 승인이 두 번 나갔다.", true);
        listener.slot(0, StarSlot.A, "주문마다 멱등 키를 두었다.", true);
        listener.slot(1, StarSlot.A, "멱등 키를 두었다.", false);

        assertThat(slotsOfFirst()).containsExactly(
                new CardDraftLive.Slot(StarField.S, 0, "결제 승인이 두 번 나갔다.", true),
                new CardDraftLive.Slot(StarField.A, 1, "멱등 키를 두었다.", false));

    }

    @Test
    @DisplayName("초안을 받으면 칸 글을 저장될 초안의 글로 맞추고 비운 칸은 빈 글로 끝내며, 스트리밍으로 받은 바퀴 번호는 그대로 둔다")
    void matchesSlotsToReceivedDraft() {

        StarDraftListener listener = log.write(JOB, 10L);
        listener.slot(1, StarSlot.A, "다시 썼다가 되돌려진 글", true);

        log.written(JOB, 10L, draftOf(Map.of(
                StarSlot.S, filled("결제 승인이 두 번 나갔다."),
                StarSlot.T, empty(),
                StarSlot.A, filled("주문마다 멱등 키를 두었다."),
                StarSlot.R, filled("중복 승인이 없어졌다."))));

        assertThat(slotsOfFirst()).containsExactly(
                new CardDraftLive.Slot(StarField.S, 0, "결제 승인이 두 번 나갔다.", true),
                new CardDraftLive.Slot(StarField.T, 0, "", true),
                new CardDraftLive.Slot(StarField.A, 1, "주문마다 멱등 키를 두었다.", true),
                new CardDraftLive.Slot(StarField.R, 0, "중복 승인이 없어졌다.", true));

    }

    @Test
    @DisplayName("근거가 없어 AI 를 부르지 않은 카드는 네 칸을 AI 가 비운 칸처럼 빈 글로 끝낸다")
    void endsNoEvidenceCardWithFourEmptySlots() {

        log.read(JOB, 10L);
        log.end(JOB, 10L, CardDraftResult.NO_EVIDENCE);

        assertThat(slotsOfFirst()).extracting(CardDraftLive.Slot::field, CardDraftLive.Slot::text, CardDraftLive.Slot::done)
                .containsExactly(tuple(StarField.S, "", true), tuple(StarField.T, "", true),
                        tuple(StarField.A, "", true), tuple(StarField.R, "", true));

    }

    @Test
    @DisplayName("단계는 카드 상세의 단계와 같은 모양으로 받은 순서대로 남기고 카드마다 60개까지만 둔다")
    void keepsStepsLikeCardDetailUpToSixty() {

        StarDraftListener listener = log.write(JOB, 10L);
        listener.step(new StarStep(1, StarStepActor.MODEL, StarStepKind.OPEN, StarSlot.R, null, "이슈 #12",
                StarMaterialKind.ISSUE, "숫자의 출처를 확인하려고"));
        for (int n = 0; n < 70; n++) {
            listener.step(new StarStep(2, StarStepActor.CODE, StarStepKind.PASS, null, null, null, null, null));
        }

        List<DraftStep> steps = log.of(JOB).getFirst().steps();
        assertThat(steps).hasSize(60);
        assertThat(steps.getFirst()).isEqualTo(new DraftStep(1, DraftStepActor.MODEL, DraftStepKind.OPEN, StarField.R,
                null, "이슈 #12", DraftMaterial.ISSUE, "숫자의 출처를 확인하려고"));

    }

    @Test
    @DisplayName("Job 이 끝나고 10분 동안은 진행을 보여 주고 그 뒤에는 빈 목록이며, 끝나지 않은 Job 은 지우지 않는다")
    void dropsJobTenMinutesAfterFinish() {

        log.start(2L, List.of(new GeneratedCard(30L, UUID.randomUUID(), 300L, "끝나지 않은 Job 의 카드")));
        log.finish(JOB);

        now.set(now.get().plus(Duration.ofMinutes(10)));
        assertThat(log.of(JOB)).hasSize(2);

        now.set(now.get().plusSeconds(1));
        assertThat(log.of(JOB)).isEmpty();
        assertThat(log.of(2L)).hasSize(1);

    }

    private List<CardDraftLiveState> states() {

        return log.of(JOB).stream().map(CardDraftLive::state).toList();

    }

    private List<CardDraftLive.Slot> slotsOfFirst() {

        return log.of(JOB).getFirst().slots();

    }

    private static StarDraft draftOf(Map<StarSlot, StarSlotDraft> slots) {

        return new StarDraft("결제 중복 승인 막기", slots, List.of(StarSlot.T), null, List.of(), List.of(), List.of(),
                List.of());

    }

    private static StarSlotDraft filled(String text) {

        return new StarSlotDraft(StarSlotStatus.FILLED, text, StarConfidence.HIGH, List.of(), null, null, null);

    }

    private static StarSlotDraft empty() {

        return new StarSlotDraft(StarSlotStatus.EMPTY, null, null, List.of(), StarEmptyReason.NO_EVIDENCE, "근거 없음",
                null);

    }
}
