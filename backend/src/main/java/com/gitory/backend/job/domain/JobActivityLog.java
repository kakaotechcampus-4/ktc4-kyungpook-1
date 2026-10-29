package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ai.AiProgressItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 분석 Job 마다 AI 가 방금 읽거나 판단한 줄을 서버 메모리에만 두었다가 Job 상세 응답에 싣고, Job 이 끝나고 10분 뒤 지운다
 * 서버가 1대라는 전제다 — 2대로 늘리면 다른 서버가 받은 줄이 보이지 않으므로 Redis 로 옮긴다
 */
@Component
@RequiredArgsConstructor
public class JobActivityLog {

    // 읽기 줄은 1초에 여러 개 와서 단계마다 최근 것만 두고, 판단 줄은 화면이 하나씩 다 보여 주도록 모두 둔다
    private static final Set<JobActivityKind> READ_KINDS = EnumSet.of(JobActivityKind.PULL_REQUEST, JobActivityKind.COMMIT);
    private static final int READ_LINES_PER_STEP = 20;
    private static final Duration KEEP_AFTER_FINISH = Duration.ofMinutes(10);

    private final Map<Long, Lines> byJob = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 종류나 뺀 이유를 모르거나 그 종류에 있어야 할 값이 빠진 줄은 남기지 않는다 */
    public void append(Long jobId, JobStepKey step, AiProgressItem item) {

        dropExpired();
        JobActivityKind kind = enumOf(JobActivityKind.class, item.kind());
        JobActivityExclusion excluded = enumOf(JobActivityExclusion.class, item.excluded());
        if (kind != null && complete(kind, item, excluded)) {
            byJob.computeIfAbsent(jobId, id -> new Lines()).add(step, kind, excluded, item);
        }

    }

    public List<JobActivity> recent(Long jobId) {

        dropExpired();
        Lines lines = byJob.get(jobId);
        return lines == null ? List.of() : lines.copy();

    }

    public void finish(Long jobId) {

        Lines lines = byJob.get(jobId);
        if (lines != null) {
            lines.finish(clock.instant());
        }

    }

    private void dropExpired() {

        Instant before = clock.instant().minus(KEEP_AFTER_FINISH);
        byJob.values().removeIf(lines -> lines.finishedBefore(before));

    }

    private static boolean complete(JobActivityKind kind, AiProgressItem item, JobActivityExclusion excluded) {

        return switch (kind) {
            case PULL_REQUEST -> allPresent(item.number(), item.title(), item.reviews());
            case COMMIT -> allPresent(item.sha(), item.title(), item.files())
                    && (item.excluded() == null || excluded != null);
            case GROUPED -> allPresent(item.units(), item.experiences());
            case DESCRIBED -> allPresent(item.title(), item.reason());
        };

    }

    private static boolean allPresent(Object... values) {

        return Stream.of(values).allMatch(Objects::nonNull);

    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {

        try {
            return name == null ? null : Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }

    }

    private static final class Lines {

        private final List<JobActivity> lines = new ArrayList<>();
        private long lastSeq;
        private Instant finishedAt;

        synchronized void add(JobStepKey step, JobActivityKind kind, JobActivityExclusion excluded,
                              AiProgressItem item) {

            lines.add(new JobActivity(++lastSeq, step, kind, item.number(), item.title(), item.reviews(), item.sha(),
                    item.files(), excluded, item.units(), item.experiences(), item.reason()));
            if (READ_KINDS.contains(kind)) {
                List<JobActivity> read = lines.stream()
                        .filter(line -> READ_KINDS.contains(line.kind()) && line.step() == step)
                        .toList();
                if (read.size() > READ_LINES_PER_STEP) {
                    lines.remove(read.getFirst());
                }
            }

        }

        synchronized List<JobActivity> copy() {

            return List.copyOf(lines);

        }

        synchronized void finish(Instant at) {

            finishedAt = at;

        }

        synchronized boolean finishedBefore(Instant before) {

            return finishedAt != null && finishedAt.isBefore(before);

        }
    }
}
