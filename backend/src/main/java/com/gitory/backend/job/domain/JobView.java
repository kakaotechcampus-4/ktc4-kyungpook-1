package com.gitory.backend.job.domain;

import com.gitory.backend.recommend.domain.CandidateVerdict;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record JobView(
        String jobId,
        JobType type,
        JobState state,
        boolean partial,
        List<JobStep> steps,
        JobErrorCode errorCode,
        Boolean retryable,
        Integer retryAfterSec,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        JobResult result,
        boolean terminal) {

    static JobView from(AnalysisJob job, UUID repoId, List<UUID> cardIds, Supplier<List<String>> emptyReasons) {

        return new JobView(
                job.getPublicId().toString(),
                job.getType(),
                job.getState(),
                job.isPartial(),
                job.getSteps(),
                job.getErrorCode(),
                retryable(job.getErrorCode()),
                job.getRetryAfterSec(),
                job.getStartedAt(),
                job.getUpdatedAt(),
                job.getFinishedAt(),
                result(job, repoId, cardIds, emptyReasons),
                job.isTerminal());

    }

    private static Boolean retryable(JobErrorCode errorCode) {

        if (errorCode == null) {
            return null;
        }

        return errorCode == JobErrorCode.GITHUB_UNAVAILABLE || errorCode == JobErrorCode.GITHUB_RATE_LIMITED
                || errorCode == JobErrorCode.AI_UNAVAILABLE;
    }

    private static JobResult result(AnalysisJob job, UUID repoId, List<UUID> cardIds,
                                    Supplier<List<String>> emptyReasons) {

        if (job.getType() == JobType.DRAFT) {
            return new JobResult(repoId.toString(), cardIds.stream().map(UUID::toString).toList(), null, null);
        }

        CandidateVerdict verdict = verdictOf(job);
        if (verdict == null) {
            return new JobResult(repoId.toString(), null, null, null);
        }

        List<String> reasons = verdict == CandidateVerdict.EMPTY ? emptyReasons.get() : List.of();

        return new JobResult(repoId.toString(), null, verdict.name(), reasons);

    }

    /** 후보를 저장한 Job 만 판정하며, 후보 보드와 같은 규칙에 후보 추리기 단계의 후보 수와 부분 수집 여부를 넣는다 */
    private static CandidateVerdict verdictOf(AnalysisJob job) {

        return job.getSteps().stream()
                .filter(step -> step.key() == JobStepKey.COMPRESS && step.state() == JobStepState.DONE)
                .findFirst()
                .map(compress -> CandidateVerdict.of(compress.done(), job.isPartial()))
                .orElse(null);

    }
}
