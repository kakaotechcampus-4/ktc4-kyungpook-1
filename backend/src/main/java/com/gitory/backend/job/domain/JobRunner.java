package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.ExperienceGroupingPort;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.GroupingMaterial;
import com.gitory.backend.ingest.domain.GroupingMaterialService;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.recommend.domain.GroupedCandidate;
import com.gitory.backend.recommend.domain.LockedSources;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대기 중인 분석 Job 하나를 꺼내 AI 수집을 부르고, 경험 그룹화를 켰으면 저장한 수집 결과로 AI 경험 그룹화까지 부른 뒤 결과에 따라 끝내며, 카드 초안 Job 은 AI 를 부르지 않고 바로 끝낸다
 * Job 을 RUNNING 으로 확정한 뒤에 AI 를 불러, 응답을 기다리는 동안 DB 커넥션과 잠금을 붙잡지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(JobGroupingProperties.class)
public class JobRunner {

    // 멈춘 Job 판정은 수집·묶기 응답 대기 한도 중 큰 값에 이 여유를 더한 시간이다
    // 묶기 직전에 갱신 시각을 새로 찍어 두 대기를 더하지 않아도 되고, 한도를 늘리면 판정도 같이 늦춰진다
    private static final Duration STUCK_MARGIN = Duration.ofMinutes(5);

    private static final Set<String> GITHUB_FAILURES =
            Set.of("GITHUB_API_ERROR", "RESOURCE_NOT_FOUND", "GITHUB_CONNECTION_UNAVAILABLE");

    private final AnalysisJobRepository jobs;
    private final RepositoryActivityPort activities;
    private final ActivityStoreService activityStore;
    private final TransactionTemplate transaction;
    private final AiClientProperties aiProperties;
    private final JobGroupingProperties groupingProperties;
    private final UserLoginService users;
    private final GroupingMaterialService materials;
    private final CandidateStoreService candidates;
    private final ExperienceGroupingPort grouping;
    private final GithubCollectionAccessPort githubAccess;

    /** 꺼낸 Job 은 RUNNING 을 커밋한 뒤에 돌려주고, 꺼낼 Job 이 없으면 빈 값을 돌려준다 */
    public Optional<AnalysisJob> claimNext() {

        return transaction.execute(status -> startNext());

    }

    public void failStuckJobs() {

        Duration longestWait = Collections.max(List.of(aiProperties.collectReadTimeout(),
                aiProperties.groupsReadTimeout()));
        Instant before = Instant.now().minus(longestWait).minus(STUCK_MARGIN);
        transaction.executeWithoutResult(status -> jobs.findStuckForUpdate(before)
                .forEach(job -> job.fail(JobErrorCode.INTERNAL_ERROR)));

    }

    /**
     * 서버가 꺼질 때 실행 중이던 Job 을 다시 대기열에 넣는다
     * 서버가 1대라는 전제다 — 2대면 다른 서버가 실행 중인 Job 까지 되돌리므로 그때는 하트비트 방식으로 바꾼다
     */
    public void requeueRunningJobs() {

        transaction.executeWithoutResult(status -> jobs.findWithLockByState(JobState.RUNNING)
                .forEach(AnalysisJob::requeue));

    }

    private Optional<AnalysisJob> startNext() {

        Optional<AnalysisJob> next = jobs.findNextQueuedForUpdate();
        next.ifPresent(AnalysisJob::start);
        return next;

    }

    /**
     * {@link #claimNext()} 로 꺼낸 Job 을 받아 끝내며, 카드 초안 Job 은 AI 를 부르지 않는 {@link #runDraft} 로 넘긴다
     * 분석 Job 은 AI 수집·경험 그룹화 결과대로 끝내고, 경험 그룹화가 실패해도 먼저 저장한 수집 결과는 남기고 다시 부르지 않고 실패로 끝낸다
     */
    public void run(AnalysisJob job) {

        if (job.getType() == JobType.DRAFT) {
            runDraft(job);
            return;
        }

        try {
            IngestRequest request = new IngestRequest(job.getUserRepositoryId(), List.of(),
                    activityStore.knownCommitShas(job.getUserRepositoryId()));
            CollectedActivity activity = activities.collect(request);
            Instant limitResetAt = limitResetAtOf(job, activity.partialReason());
            Boolean startedGrouping = transaction.execute(
                    status -> storeCollection(job.getId(), request, activity, limitResetAt));
            if (Boolean.TRUE.equals(startedGrouping)) {
                group(job, activity.partialReason(), limitResetAt);
            }
        } catch (RuntimeException failure) {
            JobErrorCode errorCode = errorCodeOf(failure);
            log.warn("분석 Job {} 을 {} 로 끝낸다", job.getId(), errorCode, failure);
            transaction.executeWithoutResult(status -> fail(job.getId(), errorCode));
        }

    }

    /**
     * 카드 초안 Job 은 AI 를 부르지 않고 끝내며, 기다리는 사이 취소돼 RUNNING 이 아니게 된 Job 이면 그대로 둔다
     * 카드에는 만들 때 1번 버전이 이미 있다
     */
    private void runDraft(AnalysisJob job) {

        try {
            transaction.executeWithoutResult(status -> finishDraft(job.getId()));
        } catch (RuntimeException failure) {
            log.warn("카드 초안 Job {} 을 {} 로 끝낸다", job.getId(), JobErrorCode.INTERNAL_ERROR, failure);
            transaction.executeWithoutResult(status -> fail(job.getId(), JobErrorCode.INTERNAL_ERROR));
        }

    }

    private void finishDraft(Long jobId) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() == JobState.RUNNING) {
            job.completeDraftWithoutWriting();
        }

    }

    /**
     * 수집 결과를 저장하고, 묶기를 켰으면 Job 을 끝내지 않고 묶기를 시작한 뒤 true 를 돌려준다
     * AI 를 기다리는 사이 취소돼 RUNNING 이 아니게 된 Job 이면 수집 결과를 저장하지 않는다
     */
    private boolean storeCollection(Long jobId, IngestRequest request, CollectedActivity activity,
                                    Instant limitResetAt) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() != JobState.RUNNING) {
            return false;
        }

        Long collectionRunId = activityStore.store(request, activity);
        job.recordCollection(collectionRunId, activityStore.storedCommitCount(request.userRepositoryId()),
                activity.pullRequests().size());
        if (groupingProperties.enabled()) {
            job.startGrouping();
            return true;
        }

        job.succeed(activity.partialReason() != null, partialErrorCodeOf(activity.partialReason()), limitResetAt);
        return false;

    }

    /**
     * 저장된 내 커밋에서 손댄 후보의 커밋·PR 을 빼고 AI 후보 묶기를 불러 묶은 후보를 저장한다
     * 보낼 요청이 지난번 묶었을 때와 같으면 AI 를 부르지 않고 지난 후보를 그대로 둔다
     */
    private void group(AnalysisJob job, PartialReason partialReason, Instant limitResetAt) {

        Long userRepositoryId = job.getUserRepositoryId();
        String login = users.loginOf(job.getUserId());
        LockedSources locked = candidates.lockedSources(userRepositoryId);
        GroupingMaterial material = materials.load(userRepositoryId, login, locked.commitShas(),
                locked.pullRequestNumbers());
        ExperienceGroupingRequest request = GroupingConverter.requestOf(userRepositoryId, login, material,
                partialReason != null);
        String fingerprint = candidates.fingerprint(GroupingConverter.jsonOf(request));

        if (candidates.keepIfSameInput(userRepositoryId, fingerprint)) {
            transaction.executeWithoutResult(status -> keepCandidates(job.getId(), partialReason, limitResetAt));
            return;
        }

        List<GroupedCandidate> grouped = GroupingConverter.candidatesOf(grouping.group(request));
        transaction.executeWithoutResult(
                status -> storeCandidates(job.getId(), fingerprint, grouped, partialReason, limitResetAt));

    }

    /** 지난 후보를 그대로 두고 Job 만 끝내며, 그사이 취소된 Job 이면 그대로 둔다 */
    private void keepCandidates(Long jobId, PartialReason partialReason, Instant limitResetAt) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() == JobState.RUNNING) {
            finishGrouping(job, partialReason, limitResetAt);
        }

    }

    /** AI 를 기다리는 사이 취소돼 RUNNING 이 아니게 된 Job 이면 묶은 후보를 저장하지 않는다 */
    private void storeCandidates(Long jobId, String fingerprint, List<GroupedCandidate> grouped,
                                 PartialReason partialReason, Instant limitResetAt) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() != JobState.RUNNING) {
            return;
        }

        candidates.store(job.getUserRepositoryId(), fingerprint, grouped);
        finishGrouping(job, partialReason, limitResetAt);

    }

    private void finishGrouping(AnalysisJob job, PartialReason partialReason, Instant limitResetAt) {

        job.recordCandidates(candidates.countCandidates(job.getUserRepositoryId()));
        job.succeed(partialReason != null, partialErrorCodeOf(partialReason), limitResetAt);

    }

    /** 저장 트랜잭션과 Job 행 잠금 밖에서 GitHub 한도 부분 결과일 때만 풀리는 시각을 묻는다 */
    private Instant limitResetAtOf(AnalysisJob job, PartialReason reason) {

        if (reason != PartialReason.GITHUB_RATE_LIMITED) {
            return null;
        }

        return githubAccess.rateLimitResetAt(job.getUserId()).orElse(null);

    }

    private void fail(Long jobId, JobErrorCode errorCode) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() == JobState.RUNNING) {
            job.fail(errorCode);
        }

    }

    /** 우리 상한은 다시 읽어도 같은 결과라 이어 읽기가 열리지 않도록 에러 코드를 남기지 않는다 */
    private static JobErrorCode partialErrorCodeOf(PartialReason reason) {

        return reason == PartialReason.GITHUB_RATE_LIMITED ? JobErrorCode.GITHUB_RATE_LIMITED : null;

    }

    private static JobErrorCode errorCodeOf(RuntimeException failure) {

        if (failure instanceof AiClientException ai && GITHUB_FAILURES.contains(ai.errorCode())) {
            return JobErrorCode.GITHUB_UNAVAILABLE;
        }

        return JobErrorCode.INTERNAL_ERROR;

    }
}
