package com.gitory.backend.job.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
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

    // DB 기본값 now() 에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약 걸림
    // start() 호출 시각이 아니라 접수(enqueue) 시각을 의미한다
    @CreationTimestamp
    private Instant startedAt;

    private Instant finishedAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private AnalysisJob(Long userId, Long userRepositoryId, String idempotencyKey) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.idempotencyKey = idempotencyKey;
        this.type = JobType.ANALYZE;
        this.state = JobState.QUEUED;
        this.steps = queuedSteps();
        this.partial = false;
    }

    public static AnalysisJob enqueue(Long userId, Long userRepositoryId, String idempotencyKey) {
        return new AnalysisJob(userId, userRepositoryId, idempotencyKey);
    }

    /**
     * QUEUED -> RUNNING, 첫 단계인 커밋 읽기를 진행 중으로 표시
     */
    public void start() {

        requireState(JobState.QUEUED);
        this.state = JobState.RUNNING;
        this.steps = steps.stream()
                .map(step -> step.key() == JobStepKey.COMMITS
                        ? new JobStep(JobStepKey.COMMITS, JobStepState.RUNNING, 0, null)
                        : step)
                .toList();

    }

    /**
     * RUNNING -> QUEUED, 진행 단계를 접수했을 때처럼 모두 QUEUED 로 되돌린다
     * 접수 시각은 그대로 둬 대기열에서 원래 순서를 지킨다
     */
    public void requeue() {

        requireState(JobState.RUNNING);
        this.state = JobState.QUEUED;
        this.steps = queuedSteps();

    }

    /**
     * 수집 기록을 연결하고 수집 두 단계는 읽은 개수로 끝낸다
     * 후보 추리기·추천 이유는 AI 기능이 아직 없어 SKIPPED 로 둔다
     */
    public void recordCollection(Long collectionRunId, int commitCount, int pullRequestCount) {

        requireState(JobState.RUNNING);
        this.collectionRunId = collectionRunId;
        this.steps = List.of(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, commitCount, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, pullRequestCount, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    /**
     * RUNNING -> SUCCEEDED, partial 과 끝난 시각 기록
     * 에러 코드는 일부만 읽은 이유(예: GitHub 한도)를 남길 때만 받는다
     */
    public void succeed(boolean partial, JobErrorCode errorCode) {

        if (errorCode != null && !partial) {
            throw new IllegalArgumentException("에러 코드는 부분 완료에만 남긴다");
        }

        requireState(JobState.RUNNING);
        this.state = JobState.SUCCEEDED;
        this.partial = partial;
        this.errorCode = errorCode;
        this.finishedAt = Instant.now();

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

    private static List<JobStep> queuedSteps() {

        List<JobStep> steps = new ArrayList<>();

        for (JobStepKey key : JobStepKey.values()) {
            steps.add(new JobStep(key, JobStepState.QUEUED, 0, null));
        }

        return steps;
    }

}