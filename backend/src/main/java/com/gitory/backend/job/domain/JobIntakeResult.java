package com.gitory.backend.job.domain;

import java.util.UUID;

/**
 * 분석 요청 접수 결과
 * created 가 false 면 새로 만들지 않고 기존 Job 을 돌려준 것으로 파악한다.
 */
public record JobIntakeResult(UUID jobId, JobState state, boolean created) {

    static JobIntakeResult newJob(AnalysisJob job) {
        return new JobIntakeResult(job.getPublicId(), job.getState(), true);
    }

    static JobIntakeResult existingJob(AnalysisJob job) {
        return new JobIntakeResult(job.getPublicId(), job.getState(), false);
    }
}