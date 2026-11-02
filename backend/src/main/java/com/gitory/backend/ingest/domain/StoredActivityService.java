package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.StoredActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 저장소 연결 하나에 저장된 커밋·바뀐 파일·PR·이슈·리뷰를 다른 모듈이 읽는 조회로, 후보를 저장하는 recommend 와 카드 초안을 만드는 job 이 쓴다 */
@Service
@RequiredArgsConstructor
public class StoredActivityService {

    // AI 가 연결 이슈는 앞 3개, 리뷰와 답 댓글은 앞 5개씩만 쓰므로 그만큼만 읽어 보낸다
    private static final int MAX_LINKED_ISSUES = 3;
    private static final int MAX_REVIEW_NOTES = 5;

    private final StoredActivityRepository storedActivity;

    /** 요청한 sha 중 저장된 커밋만 돌려주며, 바뀐 파일을 아직 채우지 않은 커밋은 파일이 빈 목록이다 */
    @Transactional(readOnly = true)
    public List<StoredCommit> findCommits(Long userRepositoryId, Collection<String> shas) {

        List<GitCommit> commits = storedActivity.findCommits(userRepositoryId, shas);
        Map<Long, List<ChangedFile>> filesByCommit = storedActivity.findFiles(commits.stream().map(GitCommit::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(CommitFile::getCommitId, Collectors.mapping(
                        file -> new ChangedFile(file.getPath(), file.getAdditions(), file.getDeletions()),
                        Collectors.toList())));

        return commits.stream()
                .map(commit -> storedCommitOf(commit, filesByCommit.getOrDefault(commit.getId(), List.of())))
                .toList();

    }

    public List<String> findShas(Collection<Long> commitIds) {

        return storedActivity.findShas(commitIds);

    }

    /** 줄 댓글이 하나도 없는 PR 은 결과에 없다 */
    public Map<Integer, Long> countReviewComments(Long userRepositoryId, Collection<Integer> pullRequestNumbers) {

        return storedActivity.countReviewComments(userRepositoryId, pullRequestNumbers).stream()
                .collect(Collectors.toMap(ReviewCommentCount::pullRequestNumber, ReviewCommentCount::count));

    }

    /** 저장된 PR 이 없으면 빈 값이다 */
    @Transactional(readOnly = true)
    public Optional<StoredPullRequest> findPullRequest(Long userRepositoryId, int number) {

        return storedActivity.findPullRequest(userRepositoryId, number);

    }

    /**
     * 카드 주인이 쓴 PR 의 연결 이슈와 리뷰·줄 댓글로, 남이 쓴 PR 이나 저장되지 않은 PR 이면 빈 값이다
     * 리뷰와 줄 댓글은 단 시각 순서로 남이 쓴 것과 카드 주인이 쓴 것을 나누고, 취소된 리뷰와 본문이 빈 리뷰는 넣지 않는다
     * 봇이 단 리뷰·줄 댓글은 사람이 남긴 지적이 아니어서 넣지 않는다
     */
    @Transactional(readOnly = true)
    public Optional<PullRequestMaterials> findOwnPullRequestMaterials(Long userRepositoryId, int number,
                                                                      String ownerLogin) {

        return storedActivity.findPullRequestRow(userRepositoryId, number)
                .filter(pullRequest -> ownerLogin.equalsIgnoreCase(pullRequest.getAuthorLogin()))
                .map(pullRequest -> materialsOf(pullRequest, ownerLogin));

    }

    private PullRequestMaterials materialsOf(PullRequest pullRequest, String ownerLogin) {

        List<ReviewNote> notes = new ArrayList<>();
        storedActivity.findReviews(pullRequest.getRepositoryId(), pullRequest.getGithubPrNumber()).stream()
                .filter(review -> review.getBodyExcerpt() != null && !review.getBodyExcerpt().isBlank())
                .forEach(review -> notes.add(
                        new ReviewNote(review.getSubmittedAt(), review.getAuthorLogin(), review.getBodyExcerpt())));
        storedActivity.findReviewComments(pullRequest.getRepositoryId(), pullRequest.getGithubPrNumber())
                .forEach(comment -> notes.add(
                        new ReviewNote(comment.getCommentedAt(), comment.getAuthorLogin(), comment.getBodyExcerpt())));
        notes.removeIf(ReviewNote::writtenByBot);
        notes.sort(Comparator.comparing(ReviewNote::at, Comparator.nullsLast(Comparator.naturalOrder())));

        return new PullRequestMaterials(linkedIssuesOf(pullRequest),
                notes.stream().filter(note -> !note.writtenBy(ownerLogin)).map(ReviewNote::body)
                        .limit(MAX_REVIEW_NOTES).toList(),
                notes.stream().filter(note -> note.writtenBy(ownerLogin)).map(ReviewNote::body)
                        .limit(MAX_REVIEW_NOTES).toList());

    }

    // 연결한 순서대로, 저장된 이슈만 담는다
    private List<PullRequestMaterials.LinkedIssue> linkedIssuesOf(PullRequest pullRequest) {

        List<Integer> numbers = pullRequest.getLinkedIssueNumbers();
        if (numbers.isEmpty()) {
            return List.of();
        }

        Map<Integer, Issue> stored = storedActivity.findIssues(pullRequest.getRepositoryId(), numbers).stream()
                .collect(Collectors.toMap(Issue::getGithubIssueNumber, Function.identity()));
        return numbers.stream()
                .distinct()
                .map(stored::get)
                .filter(Objects::nonNull)
                .limit(MAX_LINKED_ISSUES)
                .map(issue -> new PullRequestMaterials.LinkedIssue(issue.getGithubIssueNumber(), issue.getTitle(),
                        issue.getBodyExcerpt()))
                .toList();

    }

    /** sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나오므로, 요청한 sha 와 같게 비교되도록 지운다 */
    private static StoredCommit storedCommitOf(GitCommit commit, List<ChangedFile> files) {

        return new StoredCommit(commit.getId(), commit.getSha().stripTrailing(), commit.getAuthoredAt(),
                commit.getChangedFiles(), files);

    }

    private record ReviewNote(Instant at, String authorLogin, String body) {

        boolean writtenBy(String login) {

            return login.equalsIgnoreCase(authorLogin);

        }

        boolean writtenByBot() {

            return authorLogin != null && authorLogin.toLowerCase(Locale.ROOT).endsWith("[bot]");

        }
    }
}
