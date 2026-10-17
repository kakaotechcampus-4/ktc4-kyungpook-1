package com.gitory.backend.support;

import com.gitory.backend.job.domain.AnalysisJob;
import jakarta.persistence.EntityManager;

/** 후보를 저장하고 끝난 분석 Job 을 만든다 */
public final class AnalyzeJobFixtures {

    private final EntityManager em;

    public AnalyzeJobFixtures(EntityManager em) {

        this.em = em;

    }

    /** 후보 추리기까지 마치고 SUCCEEDED 로 끝난 분석 Job 이며, 후보 0개면 판정이 EMPTY 가 된다 */
    public AnalysisJob groupedJob(Long userId, Long userRepositoryId, String key, Long collectionRunId,
                                  int candidateCount, boolean partial) {

        AnalysisJob job = startedJob(userId, userRepositoryId, key, collectionRunId);
        job.startGrouping();
        job.recordCandidates(candidateCount);
        job.succeed(partial, null);

        return persisted(job);

    }

    /** 수집만 마치고 경험 그룹화를 건너뛰어 후보 판정이 없는 SUCCEEDED 분석 Job 이다 */
    public AnalysisJob ungroupedJob(Long userId, Long userRepositoryId, String key, Long collectionRunId) {

        AnalysisJob job = startedJob(userId, userRepositoryId, key, collectionRunId);
        job.succeed(false, null);

        return persisted(job);

    }

    private AnalysisJob startedJob(Long userId, Long userRepositoryId, String key, Long collectionRunId) {

        AnalysisJob job = AnalysisJob.enqueue(userId, userRepositoryId, key);
        job.start();
        job.recordCollection(collectionRunId, 3, 1);

        return job;

    }

    private AnalysisJob persisted(AnalysisJob job) {

        em.persist(job);
        em.flush();

        return job;

    }
}
