package com.gitory.backend.job.domain;


import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * 분석 요청을 받아 Job 을 만든다. 같은 요청이 여러 번 와도 Job 은 하나만 생긴다.
 * 주의: 현재 서비스 계층엔 @Transactional 을 붙이지 말 것 — 붙이면 동시 요청 때 기존 Job 을 다시 찾지 못해 에러가 난다.
 */
@Service
@RequiredArgsConstructor
public class JobIntakeService {

    private static final List<JobState> ACTIVE_STATES = List.of(JobState.QUEUED, JobState.RUNNING);

    private final AnalysisJobRepository jobRepository;

    public JobIntakeResult intake(Long userId, Long userRepositoryId, String idempotencyKey) {

        String key = (idempotencyKey != null) ? idempotencyKey : UUID.randomUUID().toString();

        Optional<JobIntakeResult> existing = findExisting(userId, userRepositoryId, key);

        if(existing.isPresent()) {
            return existing.get();
        }

        try {
            AnalysisJob job = jobRepository.save(AnalysisJob.enqueue(userId, userRepositoryId, key));
            return JobIntakeResult.newJob(job);
        }catch (DataIntegrityViolationException e) {

            /**
             * 동시 요청(uq_job_active)은 DB 유니크 제약이 하나만 저장시킨다.
             * 막힌 후 예외 처리될 경우 먼저 저장된 Job 을 돌려준다
             */

            return findExisting(userId, userRepositoryId, key).orElseThrow(() -> e);

        }
    }

    private Optional<JobIntakeResult> findExisting(Long userId, Long userRepositoryId, String key) {

        Optional<AnalysisJob> sameKey = jobRepository.findByUserIdAndIdempotencyKey(userId, key);

        if (sameKey.isEmpty()) {
            return jobRepository.findByUserRepositoryIdAndStateIn(userRepositoryId, ACTIVE_STATES)
                    .map(JobIntakeResult::existingJob);
        }

        if (!sameKey.get().getUserRepositoryId().equals(userRepositoryId)) {
            throw new IdempotencyKeyMismatchException();
        }

        return Optional.of(JobIntakeResult.existingJob(sameKey.get()));
    }
}
