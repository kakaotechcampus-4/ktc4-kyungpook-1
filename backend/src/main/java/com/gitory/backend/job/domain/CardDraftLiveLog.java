package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftListener;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.agent.port.StarStep;
import com.gitory.backend.card.domain.DraftMaterial;
import com.gitory.backend.card.domain.DraftStep;
import com.gitory.backend.card.domain.DraftStepActor;
import com.gitory.backend.card.domain.DraftStepKind;
import com.gitory.backend.card.domain.GeneratedCard;
import com.gitory.backend.card.domain.StarField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 카드 초안 Job 마다 카드별로 AI 가 쓰는 STAR 칸 글과 거친 단계를 서버 메모리에만 두었다가 Job 상세 응답에 싣고, Job 이 끝나고 10분 뒤 지운다
 * JobActivityLog 처럼 서버가 1대라는 전제다 — 2대로 늘리면 다른 서버가 받은 글이 보이지 않으므로 Redis 로 옮긴다
 */
@Component
@RequiredArgsConstructor
public class CardDraftLiveLog {

    // STAR 초안 응답의 단계 상한과 같다
    private static final int MAX_STEPS = 60;
    private static final Duration KEEP_AFTER_FINISH = Duration.ofMinutes(10);
    private static final Set<CardDraftResult> FAILURES = EnumSet.of(CardDraftResult.GITHUB_UNAUTHORIZED,
            CardDraftResult.AI_UNAVAILABLE, CardDraftResult.FAILED);

    private final Map<Long, Cards> byJob = new ConcurrentHashMap<>();
    private final Clock clock;

    public void start(Long jobId, List<GeneratedCard> cards) {

        dropExpired();
        byJob.put(jobId, new Cards(cards));

    }

    public void read(Long jobId, Long cardId) {

        update(jobId, cardId, card -> card.state = CardDraftLiveState.READING);

    }

    /** 카드를 쓰는 중으로 두고, AI 가 보내는 칸 글과 단계를 이 카드에 남기는 listener 를 돌려준다 */
    public StarDraftListener write(Long jobId, Long cardId) {

        update(jobId, cardId, card -> card.state = CardDraftLiveState.WRITING);
        return new StarDraftListener() {

            @Override
            public void slot(int round, StarSlot slot, String text, boolean done) {

                StarField field = StarField.valueOf(slot.name());
                update(jobId, cardId, card -> card.slots.put(field, new CardDraftLive.Slot(field, round, text, done)));

            }

            @Override
            public void step(StarStep step) {

                update(jobId, cardId, card -> {
                    if (card.steps.size() < MAX_STEPS) {
                        card.steps.add(draftStepOf(step));
                    }
                });

            }
        };

    }

    /** 검사에 걸려 다시 쓴 칸이 앞 글로 되돌려질 수 있어, 받은 초안의 글로 칸을 맞춰 저장될 카드와 같게 한다 */
    public void written(Long jobId, Long cardId, StarDraft draft) {

        update(jobId, cardId, card -> {
            for (StarSlot slot : StarSlot.values()) {
                StarField field = StarField.valueOf(slot.name());
                StarSlotDraft drafted = draft.slots().get(slot);
                CardDraftLive.Slot streamed = card.slots.get(field);
                card.slots.put(field, new CardDraftLive.Slot(field, streamed == null ? 0 : streamed.round(),
                        drafted.status() == StarSlotStatus.EMPTY ? "" : drafted.text(), true));
            }
        });

    }

    /** 근거가 없어 AI 를 부르지 않은 카드는 네 칸을 AI 가 비운 칸처럼 빈 글로 끝낸다 */
    public void end(Long jobId, Long cardId, CardDraftResult result) {

        update(jobId, cardId, card -> {
            card.state = FAILURES.contains(result) ? CardDraftLiveState.FAILED : CardDraftLiveState.DONE;
            if (result == CardDraftResult.NO_EVIDENCE) {
                for (StarField field : StarField.values()) {
                    card.slots.put(field, new CardDraftLive.Slot(field, 0, "", true));
                }
            }
        });

    }

    public List<CardDraftLive> of(Long jobId) {

        dropExpired();
        Cards cards = byJob.get(jobId);
        return cards == null ? List.of() : cards.copy();

    }

    public void finish(Long jobId) {

        Cards cards = byJob.get(jobId);
        if (cards != null) {
            cards.finish(clock.instant());
        }

    }

    private void update(Long jobId, Long cardId, Consumer<Card> change) {

        Cards cards = byJob.get(jobId);
        if (cards != null) {
            cards.update(cardId, change);
        }

    }

    private void dropExpired() {

        Instant before = clock.instant().minus(KEEP_AFTER_FINISH);
        byJob.values().removeIf(cards -> cards.finishedBefore(before));

    }

    private static DraftStep draftStepOf(StarStep step) {

        return new DraftStep(step.round(), DraftStepActor.valueOf(step.actor().name()),
                DraftStepKind.valueOf(step.kind().name()),
                step.slot() == null ? null : StarField.valueOf(step.slot().name()), step.code(), step.value(),
                step.material() == null ? null : DraftMaterial.valueOf(step.material().name()), step.reason());

    }

    private static final class Card {

        private final UUID publicId;
        private final String title;
        private final Map<StarField, CardDraftLive.Slot> slots = new EnumMap<>(StarField.class);
        private final List<DraftStep> steps = new ArrayList<>();
        private CardDraftLiveState state = CardDraftLiveState.WAITING;

        Card(GeneratedCard card) {
            this.publicId = card.publicId();
            this.title = card.title();
        }

        CardDraftLive copy() {

            return new CardDraftLive(publicId, title, state, List.copyOf(slots.values()), List.copyOf(steps));

        }
    }

    private static final class Cards {

        private final Map<Long, Card> byId = new LinkedHashMap<>();
        private Instant finishedAt;

        Cards(List<GeneratedCard> cards) {
            for (GeneratedCard card : cards) {
                byId.put(card.id(), new Card(card));
            }
        }

        synchronized void update(Long cardId, Consumer<Card> change) {

            Card card = byId.get(cardId);
            if (card != null) {
                change.accept(card);
            }

        }

        synchronized List<CardDraftLive> copy() {

            return byId.values().stream().map(Card::copy).toList();

        }

        synchronized void finish(Instant at) {

            finishedAt = at;

        }

        synchronized boolean finishedBefore(Instant before) {

            return finishedAt != null && finishedAt.isBefore(before);

        }
    }
}
