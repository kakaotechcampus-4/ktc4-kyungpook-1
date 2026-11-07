package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.GeneratedCard;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.job.infra.RepoRefreshRepository;
import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 끝난 Job 을 알림 한 줄로 바꿔 남기며, 커밋이 끝난 뒤 받는 이벤트에서도 쓰므로 부른 쪽 트랜잭션에 끼지 않는다
 * 알림은 남긴 순간의 레포 이름과 카드 제목을 그대로 담는다
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JobNoticeRecorder {

    private static final int TITLE_LIMIT = 3;

    private final AnalysisJobRepository jobs;
    private final RepoRefreshRepository refreshes;
    private final RepositoryReferenceService repositories;
    private final GeneratedCardQueryService generatedCards;
    private final NoticeService notices;

    /** 성공으로 끝난 카드 초안 Job 만 남기고, 스스로 다시 읽기가 만든 초안이면 종류가 REPO_REFRESHED 다 */
    public void cardsDrafted(Long jobId) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        if (job.getType() != JobType.DRAFT || job.getState() != JobState.SUCCEEDED) {
            return;
        }

        RepositoryReference repo = repositories.find(job.getUserRepositoryId()).orElse(null);
        if (repo == null) {
            return;
        }

        List<GeneratedCard> cards = generatedCards.cardsOf(job.getPublicId());
        List<String> titles = cards.stream().map(GeneratedCard::title).limit(TITLE_LIMIT).toList();
        NoticeKind kind = refreshes.existsByDraftJobId(job.getId()) ? NoticeKind.REPO_REFRESHED : NoticeKind.CARDS_DRAFTED;

        notices.leave(job.getUserId(), kind,
                NoticePayload.drafted(repo.publicId(), nameOf(repo), cards.size(), titles, job.getPublicId()));

    }

    /** 실패한 분석 Job 만 남기고, 스스로 다시 읽기가 접수한 분석이면 사용자가 누른 일이 아니라서 남기지 않는다 */
    public void analysisFailed(Long jobId) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        if (job.getType() != JobType.ANALYZE || job.getState() != JobState.FAILED
                || refreshes.findByAnalysisJobId(job.getId()).isPresent()) {
            return;
        }

        RepositoryReference repo = repositories.find(job.getUserRepositoryId()).orElse(null);
        if (repo == null) {
            return;
        }

        notices.leave(job.getUserId(), NoticeKind.ANALYSIS_FAILED,
                NoticePayload.failed(repo.publicId(), nameOf(repo), job.getPublicId(), job.getErrorCode().name()));

    }

    private static String nameOf(RepositoryReference repo) {

        return repo.ownerLogin() + "/" + repo.name();

    }
}
