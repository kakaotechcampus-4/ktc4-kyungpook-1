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
     * QUEUED -> RUNNING
     */
    public void start() {
        requireState(JobState.QUEUED);
        this.state = JobState.RUNNING;
    }

    /**
     * RUNNING -> SUCCEEDED
     * partial 과 끝난 시각 기록
     * */
    public void succeed(boolean partial) {
        requireState(JobState.RUNNING);
        this.state = JobState.SUCCEEDED;
        this.partial = partial;
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