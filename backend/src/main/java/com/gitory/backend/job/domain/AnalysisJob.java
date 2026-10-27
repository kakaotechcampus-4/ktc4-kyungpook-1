package com.gitory.backend.job.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.*;

/**
 * 분석 Job 하나. 요청을 접수하는 순간 만들어지고, 작업 진행 전 상태는 QUEUED
 * 필드를 추가하려면 마이그레이션에 컬럼부터 추가할 것  — (ddl-auto: validate).
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "analysis_job")
public class AnalysisJob {

    // 분석은 AI 가 PR 을 먼저 읽어 커밋과 이을 PR 을 알아 둔 뒤 커밋을 읽으므로 화면 단계도 그 순서로 둔다
    private static final List<JobStepKey> ANALYZE_ORDER =
            List.of(JobStepKey.PR_REVIEW, JobStepKey.COMMITS, JobStepKey.COMPRESS, JobStepKey.REASON);
    private static final List<JobStepKey> DRAFT_ORDER =
            List.of(JobStepKey.COMMITS, JobStepKey.PR_REVIEW, JobStepKey.COMPRESS, JobStepKey.REASON);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // URL 에는 내부 id 대신 이 값(UUID)만 내보낸다
    private UUID publicId;

    private Long userId;
    private Long userRepositoryId;
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private JobType type;

    @Enumerated(EnumType.STRING)
    private JobState state;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<JobStep> steps;

    private boolean partial;

    private Long collectionRunId;

    @Enumerated(EnumType.STRING)
    private JobErrorCode errorCode;

    private Integer retryAfterSec;

    // DB 기본값 now() 에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약 걸림
    // start() 호출 시각이 아니라 접수(enqueue) 시각을 의미한다
    @CreationTimestamp
    private Instant startedAt;

    private Instant finishedAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private AnalysisJob(Long userId, Long userRepositoryId, String idempotencyKey, JobType type) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.state = JobState.QUEUED;
        this.steps = queuedSteps(type);
        this.partial = false;
    }

    public static AnalysisJob enqueue(Long userId, Long userRepositoryId, String idempotencyKey) {

        return new AnalysisJob(userId, userRepositoryId, idempotencyKey, JobType.ANALYZE);

    }

    /** 후보로 카드를 만들 때 접수하는 카드 초안 Job 이며, 한 저장소에 여럿 있을 수 있다 */
    public static AnalysisJob enqueueDraft(Long userId, Long userRepositoryId, String idempotencyKey) {

        return new AnalysisJob(userId, userRepositoryId, idempotencyKey, JobType.DRAFT);

    }

    /**
     * QUEUED -> RUNNING, 첫 단계(분석은 PR·리뷰 읽기, 카드 초안은 커밋 읽기)를 진행 중으로 표시
     */
    public void start() {

        requireState(JobState.QUEUED);
        this.state = JobState.RUNNING;
        JobStepKey first = orderOf(type).getFirst();
        this.steps = steps.stream()
                .map(step -> step.key() == first ? new JobStep(first, JobStepState.RUNNING, 0, null) : step)
                .toList();

    }

    /**
     * RUNNING -> QUEUED, 진행 단계를 접수했을 때처럼 모두 QUEUED 로 되돌린다
     * 접수 시각은 그대로 둬 대기열에서 원래 순서를 지킨다
     */
    public void requeue() {

        requireState(JobState.RUNNING);
        this.state = JobState.QUEUED;
        this.steps = queuedSteps(type);

    }

    /**
     * 수집 기록을 연결하고 PR·커밋 단계를 이번에 새로 읽은 수(읽은 수와 전체가 같음)로 끝내, 진행 중에 보이던 수와 이어지게 한다
     * 후보 추리기·추천 이유는 경험 그룹화를 끈 채 끝나도록 SKIPPED 로 두고, 경험 그룹화를 하면 {@link #startGrouping()} 이 바꾼다
     */
    public void recordCollection(Long collectionRunId, int commitCount, int pullRequestCount) {

        requireState(JobState.RUNNING);
        this.collectionRunId = collectionRunId;
        this.steps = List.of(
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, pullRequestCount, pullRequestCount),
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, commitCount, commitCount),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    /**
     * AI 가 보낸 진행으로 그 단계를 진행 중으로, 앞 단계를 다 읽은 것으로 바꾼다
     * 뒤 단계는 건드리지 않아, 경험 그룹화를 꺼서 건너뛴 단계(SKIPPED)는 그대로 남는다
     */
    public void recordProgress(JobStepKey key, int done, Integer total) {

        requireState(JobState.RUNNING);
        if (type != JobType.ANALYZE) {
            throw new IllegalStateException("분석 Job 이 아니다: " + type);
        }
        int target = ANALYZE_ORDER.indexOf(key);
        this.steps = steps.stream()
                .map(step -> {
                    int order = ANALYZE_ORDER.indexOf(step.key());
                    if (order == target) {
                        return new JobStep(key, JobStepState.RUNNING, done, total);
                    }
                    return order < target && step.state() != JobStepState.SKIPPED ? finished(step) : step;
                })
                .toList();

    }

    /**
     * 후보 추리기를 진행 중으로, 추천 이유를 대기로 바꾼다
     * 이 변경으로 갱신 시각이 새로 찍혀, 멈춘 Job 판정은 수집이 아니라 경험 그룹화를 기다린 시간부터 센다
     */
    public void startGrouping() {

        requireState(JobState.RUNNING);
        this.steps = withCandidateSteps(JobStepState.RUNNING, JobStepState.QUEUED, 0);

    }

    /**
     * 후보 추리기·추천 이유를 끝내며, AI 가 진행을 보낸 단계는 받은 전체 수로, 못 받은 단계는 저장소 후보 수로 끝낸다
     * 저장소 후보 수는 상태와 상관없이 그 저장소 후보를 모두 센 값이고, 후보가 없으면 추천 이유만 SKIPPED 로 둔다
     */
    public void recordCandidates(int candidateCount) {

        requireState(JobState.RUNNING);
        JobStepState reason = candidateCount == 0 ? JobStepState.SKIPPED : JobStepState.DONE;
        this.steps = steps.stream()
                .map(step -> switch (step.key()) {
                    case COMPRESS -> candidateStep(step, JobStepState.DONE, candidateCount);
                    case REASON -> candidateStep(step, reason, candidateCount);
                    default -> step;
                })
                .toList();

    }

    /**
     * 카드 초안 Job 을 AI 없이 네 단계를 모두 SKIPPED 로 두고 부분 완료 없이 끝낸다
     * 카드에 만들 때 1번 버전이 이미 있어서 이 Job 이 쓸 글이 없다
     */
    public void completeDraftWithoutWriting() {

        requireState(JobState.RUNNING);
        if (type != JobType.DRAFT) {
            throw new IllegalStateException("카드 초안 Job 이 아니다: " + type);
        }

        this.steps = Arrays.stream(JobStepKey.values())
                .map(key -> new JobStep(key, JobStepState.SKIPPED, 0, null))
                .toList();
        succeed(false, null);

    }

    /**
     * 카드 초안 진행을 처리를 마친 카드 수로 남기며, 커밋 읽기는 diff 근거까지, 후보로 추리기·추천 이유 붙이기는 저장까지 마친 카드 수다
     * 카드 한 장마다 부르면 갱신 시각이 새로 찍혀, 카드가 여러 장이어도 멈춘 Job 판정에 걸리지 않는다
     */
    public void recordDraftProgress(int read, int finished, int total) {

        requireRunningDraft();
        this.steps = draftSteps(read, finished, total);

    }

    /**
     * 카드마다의 결과로 초안 Job 을 끝내며, 모든 카드가 실패면 실패이고 하나라도 덜 된 카드가 있으면 부분 완료다
     * 이유가 여럿이면 AI 오류, 시간 초과, 근거 없음 순서로 하나만 남긴다
     */
    public void finishDraft(List<CardDraftResult> results) {

        requireRunningDraft();
        this.steps = draftSteps(results.size(), results.size(), results.size());

        if (!results.isEmpty() && results.stream().allMatch(AnalysisJob::isDraftFailure)) {
            // 실패한 카드가 모두 AI 일시 장애면 다시 시도할 수 있는 AI_UNAVAILABLE 로 끝낸다
            fail(results.contains(CardDraftResult.FAILED) ? JobErrorCode.INTERNAL_ERROR : JobErrorCode.AI_UNAVAILABLE);
            return;
        }

        JobErrorCode errorCode = draftErrorCodeOf(results);
        succeed(errorCode != null, errorCode);

    }

    /**
     * RUNNING -> SUCCEEDED, partial 과 끝난 시각 기록
     * 에러 코드는 일부만 읽은 이유(예: GitHub 한도)를 남길 때만 받는다
     */
    public void succeed(boolean partial, JobErrorCode errorCode) {

        succeed(partial, errorCode, null);

    }

    /**
     * 끝난 시각부터 한도가 풀리는 시각까지 남은 초를 올림해 남기며, 이미 지났거나 모르면 비워 둔다
     * 풀리는 시각은 GitHub 한도로 일부만 읽은 부분 완료에만 받는다
     */
    public void succeed(boolean partial, JobErrorCode errorCode, Instant limitResetAt) {

        if (errorCode != null && !partial) {
            throw new IllegalArgumentException("에러 코드는 부분 완료에만 남긴다");
        }

        if (limitResetAt != null && errorCode != JobErrorCode.GITHUB_RATE_LIMITED) {
            throw new IllegalArgumentException("한도가 풀리는 시각은 GitHub 한도 부분 완료에만 남긴다");
        }

        requireState(JobState.RUNNING);
        this.state = JobState.SUCCEEDED;
        this.partial = partial;
        this.errorCode = errorCode;
        this.finishedAt = Instant.now();
        this.retryAfterSec = secondsUntil(limitResetAt);

    }

    /**
     * RUNNING → FAILED, 실패 이유와 끝난 시각 기록
     * 실패 시 에러코드 필수
     */
    public void fail(JobErrorCode errorCode) {

        if (errorCode == null) {
            throw new IllegalArgumentException("실패한 Job 에는 errorCode 가 필요하다");
        }

        requireState(JobState.RUNNING);
        this.state = JobState.FAILED;
        this.errorCode = errorCode;
        this.finishedAt = Instant.now();
    }

    /**
     * QUEUED·RUNNING -> CANCELED, 끝난 시각 기록
     * 이미 끝난 Job 은 그대로 둔다 — 화면이 2초마다 갱신돼 끝난 직후에 취소가 올 수 있다
     */
    public void cancel() {

        if (isTerminal()) {
            return;
        }

        this.state = JobState.CANCELED;
        this.finishedAt = Instant.now();

    }

    /**
     * 이미 끝난 작업(SUCCEEDED/FAILED/CANCELED)인지 검사
     */
    public boolean isTerminal() {
        return state == JobState.SUCCEEDED || state == JobState.FAILED || state == JobState.CANCELED;
    }

    /**
     * 현재 상태와 예상 상태가 동일한지를 검사
     */
    private void requireState(JobState expected) {
        if (state != expected) {
            throw new IllegalStateException("Job 상태가 " + expected + " 가 아니다: " + state);
        }
    }

    private Integer secondsUntil(Instant limitResetAt) {

        if (limitResetAt == null) {
            return null;
        }

        Duration wait = Duration.between(finishedAt, limitResetAt);
        if (wait.isZero() || wait.isNegative()) {
            return null;
        }

        return (int) Math.min(wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0), Integer.MAX_VALUE);

    }

    private void requireRunningDraft() {

        requireState(JobState.RUNNING);
        if (type != JobType.DRAFT) {
            throw new IllegalStateException("카드 초안 Job 이 아니다: " + type);
        }

    }

    // 초안 단계에서는 PR 을 다시 읽지 않는다
    private static List<JobStep> draftSteps(int read, int finished, int total) {

        return List.of(
                new JobStep(JobStepKey.COMMITS, draftStepState(read, total, true), read, total),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, draftStepState(finished, total, read > 0), finished, total),
                new JobStep(JobStepKey.REASON, draftStepState(finished, total, finished > 0), finished, total));

    }

    private static JobStepState draftStepState(int done, int total, boolean started) {

        if (done == total) {
            return JobStepState.DONE;
        }

        return started ? JobStepState.RUNNING : JobStepState.QUEUED;

    }

    private static boolean isDraftFailure(CardDraftResult result) {

        return result == CardDraftResult.FAILED || result == CardDraftResult.AI_UNAVAILABLE;

    }

    private static JobErrorCode draftErrorCodeOf(List<CardDraftResult> results) {

        if (results.contains(CardDraftResult.FAILED)) {
            return JobErrorCode.INTERNAL_ERROR;
        }

        if (results.contains(CardDraftResult.AI_UNAVAILABLE)) {
            return JobErrorCode.AI_UNAVAILABLE;
        }

        if (results.contains(CardDraftResult.TIMED_OUT)) {
            return JobErrorCode.DRAFT_TIMEOUT;
        }

        return results.contains(CardDraftResult.NO_EVIDENCE) ? JobErrorCode.EVIDENCE_MISSING : null;

    }

    private static List<JobStep> queuedSteps(JobType type) {

        List<JobStep> steps = new ArrayList<>();

        for (JobStepKey key : orderOf(type)) {
            steps.add(new JobStep(key, JobStepState.QUEUED, 0, null));
        }

        return steps;
    }

    private static List<JobStepKey> orderOf(JobType type) {

        return type == JobType.DRAFT ? DRAFT_ORDER : ANALYZE_ORDER;

    }

    private static JobStep finished(JobStep step) {

        int done = step.total() != null ? step.total() : step.done();
        return new JobStep(step.key(), JobStepState.DONE, done, step.total());

    }

    // 진행을 끄거나 같은 입력이라 AI 를 부르지 않아 전체 수를 못 받았으면 후보 수로 끝낸다
    private static JobStep candidateStep(JobStep step, JobStepState state, int candidateCount) {

        if (step.total() != null) {
            return new JobStep(step.key(), state, step.total(), step.total());
        }

        return new JobStep(step.key(), state, candidateCount, null);

    }

    private List<JobStep> withCandidateSteps(JobStepState compress, JobStepState reason, int done) {

        return steps.stream()
                .map(step -> switch (step.key()) {
                    case COMPRESS -> new JobStep(JobStepKey.COMPRESS, compress, done, null);
                    case REASON -> new JobStep(JobStepKey.REASON, reason, done, null);
                    default -> step;
                })
                .toList();

    }

}