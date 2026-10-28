package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CollectionRunRepository;
import com.gitory.backend.ingest.infra.CommitFileRepository;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.infra.IssueRepository;
import com.gitory.backend.ingest.infra.PrReviewCommentRepository;
import com.gitory.backend.ingest.infra.PrReviewRepository;
import com.gitory.backend.ingest.infra.PullRequestRepository;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedFile;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.CollectedReview;
import com.gitory.backend.ingest.port.CollectedReviewComment;
import com.gitory.backend.ingest.port.IngestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 가 돌려준 수집 결과를 수집 이력 한 건과 커밋·커밋 파일·PR·이슈·리뷰·리뷰 댓글로 저장한다
 * 커밋은 GitHub 에 이미 일어난 사실이라 이미 저장한 sha 는 다시 저장하지 않고, PR·이슈·리뷰·댓글은 닫히거나 고쳐지므로 이번 수집 값으로 덮어쓴다
 * 이번 응답에 없는 PR·이슈는 최근 것만 받아 빠졌을 수 있어 지우지 않고, 리뷰·댓글도 지우지 않아 GitHub 에서 지운 줄 댓글은 남는다
 */
@Service
@RequiredArgsConstructor
public class ActivityStoreService {

    // 팀 저장소 실측으로 커밋 하나가 바꾼 파일은 많아야 120개라 넉넉히 잡되, GitHub 이 주는 최대 3,000개를 모두 행으로 만들지는 않는다
    static final int MAX_FILES_PER_COMMIT = 300;

    // UNIQUE (commit_id, path) 인덱스는 한 항목이 2,704바이트를 넘으면 INSERT 가 실패해 같은 수집 저장 전체가 되돌아가므로, 여유를 두고 이보다 긴 경로의 파일은 저장하지 않는다
    static final int MAX_PATH_BYTES = 2048;

    static final int KNOWN_COMMIT_LIMIT = 10_000;

    private final ConnectedRepositoryRepository connectedRepositories;
    private final CollectionRunRepository runs;
    private final GitCommitRepository commits;
    private final CommitFileRepository commitFiles;
    private final PullRequestRepository pullRequests;
    private final PrReviewRepository reviews;
    private final PrReviewCommentRepository reviewComments;
    private final IssueRepository issues;

    /**
     * AI 가 상세 조회를 건너뛸 커밋의 sha 를 최근 저장한 것부터 돌려준다
     * 커밋은 저장소마다 한 벌이라 다른 사용자가 먼저 저장한 것도 포함하고, 파일·이슈 번호를 아직 못 채운 커밋은 AI 가 상세를 다시 보내도록 뺀다
     * AI 요청이 받는 최대 개수를 넘으면 요청 전체가 거절되어 상한까지만 보낸다
     */
    @Transactional(readOnly = true)
    public List<String> knownCommitShas(Long userRepositoryId) {

        ConnectedRepository connected = connectedRepositoryOf(userRepositoryId);
        return commits.findKnownShas(connected.getRepositoryId(), Limit.of(KNOWN_COMMIT_LIMIT));

    }

    @Transactional
    public Long store(IngestRequest request, CollectedActivity activity) {

        ConnectedRepository connected = connectedRepositoryOf(request.userRepositoryId());
        CollectionRun run = runs.save(runOf(request, activity));

        storeNewCommits(connected.getRepositoryId(), run.getId(), activity.commits());
        fillCommitDetails(connected.getRepositoryId(), activity.commits());
        storePullRequests(connected.getRepositoryId(), run.getId(), activity.pullRequests());
        storeReviews(connected.getRepositoryId(), run.getId(), activity.pullRequests());
        storeReviewComments(connected.getRepositoryId(), run.getId(), activity.pullRequests());
        storeIssues(connected.getRepositoryId(), run.getId(), activity.issues());
        connected.markAnalyzed();

        return run.getId();
    }

    private ConnectedRepository connectedRepositoryOf(Long userRepositoryId) {

        return connectedRepositories.findById(userRepositoryId)
                .orElseThrow(() -> new IllegalStateException("연결된 저장소가 없다: " + userRepositoryId));
    }

    private CollectionRun runOf(IngestRequest request, CollectedActivity activity) {

        return CollectionRun.recorded(request.userRepositoryId(), request.branches(), null, activity.headSha(),
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
     * 이슈 번호가 비어 있는 커밋에만 이슈 번호와 바뀐 파일을 채워, 저장만 하고 아직 채우지 않은 커밋도 다시 수집될 때 한 번 채운다
     * 채운 Job 만 커밋 id 를 돌려받아 파일을 저장하므로, 두 Job 이 같은 커밋을 함께 채워도 파일은 한 번만 들어간다
     * 두 Job 이 커밋 행을 같은 순서로 잠그도록 sha 순으로 채운다
     */
    private void fillCommitDetails(Long repositoryId, List<CollectedCommit> collected) {

        List<String> shas = collected.stream().map(CollectedCommit::sha).toList();
        Set<String> unfilled = new HashSet<>(commits.findUnfilledShas(repositoryId, shas));
        List<CollectedCommit> bySha = collected.stream().sorted(Comparator.comparing(CollectedCommit::sha)).toList();

        for (CollectedCommit commit : bySha) {
            if (!unfilled.remove(commit.sha())) {
                continue;
            }
            commits.fillIssueNumbers(repositoryId, commit.sha(), commit.issueNumbers().toArray(Integer[]::new))
                    .ifPresent(commitId -> storeFiles(commitId, commit.files()));
        }

    }

    private void storeFiles(Long commitId, List<CollectedFile> files) {

        files.stream()
                .filter(file -> file.path().getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_BYTES)
                .limit(MAX_FILES_PER_COMMIT)
                .forEach(file -> commitFiles.insertIfAbsent(CommitFile.collected(commitId, file.path(), file.status(),
                        file.additions(), file.deletions())));

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

    /**
     * 리뷰 행은 PR 행을 참조하므로 PR 을 저장한 뒤에 저장하고, PR 과 같은 이유로 GitHub 리뷰 id 순으로 저장한다
     * 상태를 몰라 null 로 온 리뷰는 CHECK 에 걸리지 않게 리뷰 행만 건너뛴다
     */
    private void storeReviews(Long repositoryId, Long collectionRunId, List<CollectedPullRequest> collected) {

        collected.stream()
                .flatMap(pr -> pr.reviews().stream()
                        .filter(review -> review.state() != null)
                        .map(review -> reviewOf(repositoryId, collectionRunId, pr.number(), review)))
                .sorted(Comparator.comparingLong(PrReview::getGithubReviewId))
                .forEach(reviews::upsert);

    }

    /**
     * 줄 댓글의 내용은 리뷰 상태와 상관없이 쓸 수 있어, 리뷰 행을 건너뛴 리뷰의 댓글도 저장한다
     * 리뷰와 같은 이유로 PR 을 저장한 뒤 GitHub 댓글 id 순으로 저장한다
     */
    private void storeReviewComments(Long repositoryId, Long collectionRunId, List<CollectedPullRequest> collected) {

        collected.stream()
                .flatMap(pr -> pr.reviews().stream()
                        .flatMap(review -> review.comments().stream())
                        .map(comment -> commentOf(repositoryId, collectionRunId, pr.number(), comment)))
                .sorted(Comparator.comparingLong(PrReviewComment::getGithubCommentId))
                .forEach(reviewComments::upsert);

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

    private PrReview reviewOf(Long repositoryId, Long collectionRunId, int prNumber, CollectedReview review) {

        return PrReview.collected(repositoryId, prNumber, collectionRunId, review.reviewId(), review.state(),
                review.authorLogin(), review.bodyExcerpt(), review.submittedAt());

    }

    private PrReviewComment commentOf(Long repositoryId, Long collectionRunId, int prNumber,
                                      CollectedReviewComment comment) {

        return PrReviewComment.collected(repositoryId, prNumber, collectionRunId, comment.commentId(),
                comment.reviewId(), comment.authorLogin(), comment.bodyExcerpt(), comment.path(), comment.line(),
                comment.commentedAt());

    }
}
