package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.job.infra.RepoRefreshRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 스스로 다시 읽기 기록을 바꾸는 일을 각각 새 트랜잭션으로 한다
 * 커밋이 끝난 뒤 받는 이벤트에서도 쓰므로 부른 쪽 트랜잭션에 끼지 않는다
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class RepoRefreshRecorder {

    private final RepoRefreshRepository refreshes;
    private final AnalysisJobRepository jobs;
    private final CardCreationService cardCreation;
    private final Clock clock;

    /** 카드와 초안 Job 을 만드는 일과 기록을 DRAFTING 으로 바꾸는 일이 한 트랜잭션이라, 초안 Job 이 먼저 끝나 기록을 못 찾는 일이 없다 */
    public void startDrafting(Long analysisJobId, Long userId, Long userRepositoryId, List<UUID> candidateIds) {

        RepoRefresh refresh = refreshes.findWithLockByAnalysisJobId(analysisJobId).orElseThrow();
        if (refresh.getState() != RepoRefreshState.READING) {
            return;
        }

        CreatedCards created = cardCreation.create(userId, userRepositoryId, candidateIds);
        AnalysisJob draft = jobs.findByPublicId(created.jobId()).orElseThrow();
        refresh.startDrafting(draft.getId(), created.cardIds().size());

    }

    /** 분석이 끝난 뒤 알릴 것이 없을 때 부르며, 이미 카드 초안으로 넘어간 기록은 그대로 둔다 */
    public void closeReading(Long analysisJobId) {

        refreshes.findWithLockByAnalysisJobId(analysisJobId)
                .filter(refresh -> refresh.getState() == RepoRefreshState.READING)
                .ifPresent(refresh -> refresh.close(Instant.now(clock)));

    }

    /** 분석 Job 이 실패·취소로 끝났거나 카드 초안 Job 이 끝났을 때 부르며, 초안이 성공으로 끝난 기록만 알릴 것이 된다 */
    public void settle(Long jobId) {

        Instant now = Instant.now(clock);
        closeReading(jobId);
        refreshes.findWithLockByDraftJobId(jobId)
                .filter(refresh -> refresh.getState() == RepoRefreshState.DRAFTING)
                .ifPresent(refresh -> {
                    if (jobs.findById(jobId).orElseThrow().getState() == JobState.SUCCEEDED) {
                        refresh.ready(now);
                    } else {
                        refresh.close(now);
                    }
                });

    }
}
