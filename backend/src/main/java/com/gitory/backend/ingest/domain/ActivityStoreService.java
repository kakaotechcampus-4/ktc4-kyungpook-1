package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CollectionRunRepository;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.infra.IssueRepository;
import com.gitory.backend.ingest.infra.PullRequestRepository;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.IngestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 가 돌려준 수집 결과를 수집 이력 한 건과 커밋·PR·이슈로 저장한다
 * 커밋은 GitHub 에 이미 일어난 사실이라 이미 저장한 sha 는 다시 저장하지 않고, PR·이슈는 열림에서 닫힘으로 바뀌므로 이번 수집 값으로 덮어쓴다
 * 이번 응답에 없는 PR·이슈는 최근 것만 받아 빠졌을 뿐이라 지우지 않는다
 */
@Service
@RequiredArgsConstructor
public class ActivityStoreService {

    private final ConnectedRepositoryRepository connectedRepositories;
    private final CollectionRunRepository runs;
    private final GitCommitRepository commits;
    private final PullRequestRepository pullRequests;
    private final IssueRepository issues;

    @Transactional
    public Long store(IngestRequest request, CollectedActivity activity) {

        ConnectedRepository connected = connectedRepositoryOf(request.userRepositoryId());
        CollectionRun run = runs.save(runOf(request, activity));

        storeNewCommits(connected.getRepositoryId(), run.getId(), activity.commits());
        storePullRequests(connected.getRepositoryId(), run.getId(), activity.pullRequests());
        storeIssues(connected.getRepositoryId(), run.getId(), activity.issues());
        connected.markAnalyzed();

        return run.getId();
    }

    private ConnectedRepository connectedRepositoryOf(Long userRepositoryId) {

        return connectedRepositories.findById(userRepositoryId)
                .orElseThrow(() -> new IllegalStateException("연결된 저장소가 없다: " + userRepositoryId));
    }

    private CollectionRun runOf(IngestRequest request, CollectedActivity activity) {

        return CollectionRun.recorded(request.userRepositoryId(), request.branches(), request.since(), null,
                activity.commits().size(), activity.pullRequests().size(), activity.issues().size(),
                activity.partialReason());
    }

    private void storeNewCommits(Long repositoryId, Long collectionRunId, List<CollectedCommit> collected) {

        List<String> shas = collected.stream().map(CollectedCommit::sha).toList();
        Set<String> stored = new HashSet<>(commits.findStoredShas(repositoryId, shas));

        for (CollectedCommit commit : collected) {
            if (stored.contains(commit.sha())) {
                continue;
            }
            stored.add(commit.sha());
            commits.insertIfAbsent(commitOf(repositoryId, collectionRunId, commit));
        }
    }

    /**
     * 두 Job 이 같은 저장소를 함께 저장할 때 행을 같은 순서로 잠그도록 번호순으로 저장한다
     * 순서가 다르면 서로 상대가 잡은 행을 기다리다 한쪽 저장이 실패한다
     */
    private void storePullRequests(Long repositoryId, Long collectionRunId, List<CollectedPullRequest> collected) {

        collected.stream()
                .sorted(Comparator.comparingInt(CollectedPullRequest::number))
                .forEach(pr -> pullRequests.upsert(repositoryId, collectionRunId, pr.number(), pr.title(),
                        pr.bodyExcerpt(), pr.state().name(), pr.authorLogin(), pr.baseBranch(), pr.headBranch(),
                        pr.openedAt(), pr.mergedAt(), pr.commitShas().toArray(String[]::new),
                        pr.linkedIssueNumbers().toArray(Integer[]::new)));

    }

    /** PR 과 같은 이유로 번호순으로 저장한다 */
    private void storeIssues(Long repositoryId, Long collectionRunId, List<CollectedIssue> collected) {

        collected.stream()
                .sorted(Comparator.comparingInt(CollectedIssue::issueNumber))
                .forEach(issue -> issues.upsert(repositoryId, collectionRunId, issue.issueNumber(), issue.title(),
                        issue.bodyExcerpt(), issue.state().name(), issue.authorLogin(),
                        issue.labels().toArray(String[]::new), issue.openedAt(), issue.closedAt()));

    }

    private GitCommit commitOf(Long repositoryId, Long collectionRunId, CollectedCommit commit) {

        return GitCommit.collected(repositoryId, collectionRunId, commit.sha(), commit.authorLogin(),
                commit.authorName(), commit.message(), commit.authoredAt(), commit.additions(),
                commit.deletions(), commit.changedFiles(), commit.parentCount(), commit.exclusionReason());
    }
}
