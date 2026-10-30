package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.GroupingMaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 저장소 연결 하나에 저장된 수집 결과로 AI 경험 그룹화에 보낼 내 커밋과, 그 커밋이 가리키는 PR·이슈의 제목·본문을 모은다
 * ingest 는 후보를 모르므로 손댄 후보에 든 커밋 sha 와 손댄 후보가 담은 PR 번호는 부르는 쪽이 넘긴다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupingMaterialService {

    private final GroupingMaterialRepository materials;

    /** lockedShas 에 든 커밋과, 커밋의 PR 이 lockedPullRequestNumbers 에 든 커밋은 빼고 모은다 */
    @Transactional(readOnly = true)
    public GroupingMaterial load(Long userRepositoryId, String login, Set<String> lockedShas,
                                 Set<Integer> lockedPullRequestNumbers) {

        GithubRepo repository = materials.findRepository(userRepositoryId)
                .orElseThrow(() -> new IllegalStateException("연결된 저장소가 없다: " + userRepositoryId));
        List<PullRequest> pullRequests = materials.findPullRequests(repository.getId());
        PullRequestBinding binding = new PullRequestBinding(pullRequests, login, repository.getDefaultBranch());
        Map<Integer, Issue> issues = materials.findIssues(repository.getId()).stream()
                .collect(Collectors.toMap(Issue::getGithubIssueNumber, Function.identity()));

        List<GitCommit> ownCommits = materials.findOwnCommits(repository.getId(), login);
        Map<Long, List<MaterialFile>> files = filesOf(ownCommits);
        List<MaterialCommit> commits = new ArrayList<>();
        for (GitCommit commit : ownCommits) {
            // sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나와, PR 커밋 목록·넘겨받은 sha 와 같게 비교되도록 지운다
            String sha = commit.getSha().stripTrailing();
            // issue_numbers 는 AI 가 메시지에서 뽑은 '#숫자' 전부라 이슈 번호와 PR 번호가 섞여 있다
            List<Integer> references = Objects.requireNonNullElse(commit.getIssueNumbers(), List.of());

            Integer pullRequestNumber = binding.pullRequestOf(sha, references);
            // 손댄 후보의 PR 을 커밋의 PR 로 보내면 AI 가 그 PR 후보를 다시 만든다
            // 손댄 PR 에도 들었지만 커밋이 더 적은 다른 PR 이 커밋의 PR 이면(쌓은 PR) 빼지 않아 그 PR 의 후보가 사라지지 않게 한다
            if (lockedShas.contains(sha)
                    || (pullRequestNumber != null && lockedPullRequestNumbers.contains(pullRequestNumber))) {
                continue;
            }

            // AI 요청은 메시지 칸이 null 이면 거절해 빈 글자로 보낸다
            commits.add(new MaterialCommit(sha, Objects.requireNonNullElse(commit.getMessage(), ""),
                    commit.getAuthorLogin(), commit.getAuthorName(), commit.getAuthoredAt(), commit.getParentCount(),
                    commit.getAdditions(), commit.getDeletions(), commit.getChangedFiles(),
                    files.getOrDefault(commit.getId(), List.of()), pullRequestNumber,
                    references.stream().filter(issues::containsKey).toList()));
        }

        List<MaterialPullRequest> pullRequestContexts = pullRequestContexts(commits, pullRequests, issues);
        List<MaterialIssue> issueContexts = issueContexts(commits, pullRequestContexts, issues);
        log.info("저장소 연결 {} 의 경험 그룹화에 보낼 내 커밋 {}개, PR {}개, 이슈 {}개를 모았다", userRepositoryId,
                commits.size(), pullRequestContexts.size(), issueContexts.size());

        return new GroupingMaterial(commits, pullRequestContexts, issueContexts);

    }

    private Map<Long, List<MaterialFile>> filesOf(List<GitCommit> commits) {

        return materials.findFiles(commits.stream().map(GitCommit::getId).toList()).stream()
                .collect(Collectors.groupingBy(CommitFile::getCommitId, Collectors.mapping(
                        file -> new MaterialFile(file.getPath(), file.getStatus(), file.getAdditions(),
                                file.getDeletions()),
                        Collectors.toList())));

    }

    /** 보내는 커밋이 가리키지 않는 PR 은 빼, 상관없는 PR 이 바뀌어도 요청이 그대로이게 한다 */
    private static List<MaterialPullRequest> pullRequestContexts(List<MaterialCommit> commits,
                                                                 List<PullRequest> pullRequests,
                                                                 Map<Integer, Issue> issues) {

        Set<Integer> numbers = commits.stream()
                .map(MaterialCommit::pullRequestNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return pullRequests.stream()
                .filter(pullRequest -> numbers.contains(pullRequest.getGithubPrNumber()))
                .sorted(Comparator.comparingInt(PullRequest::getGithubPrNumber))
                .map(pullRequest -> new MaterialPullRequest(pullRequest.getGithubPrNumber(), pullRequest.getTitle(),
                        pullRequest.getBodyExcerpt(),
                        pullRequest.getLinkedIssueNumbers().stream().filter(issues::containsKey).toList()))
                .toList();

    }

    /** PR 과 같은 이유로, 보내는 커밋과 PR 이 가리키는 이슈만 넣는다 */
    private static List<MaterialIssue> issueContexts(List<MaterialCommit> commits,
                                                     List<MaterialPullRequest> pullRequests,
                                                     Map<Integer, Issue> issues) {

        return Stream.concat(commits.stream().flatMap(commit -> commit.issueNumbers().stream()),
                        pullRequests.stream().flatMap(pullRequest -> pullRequest.linkedIssueNumbers().stream()))
                .distinct()
                .sorted()
                .map(issues::get)
                .map(issue -> new MaterialIssue(issue.getGithubIssueNumber(), issue.getTitle(),
                        issue.getBodyExcerpt()))
                .toList();

    }
}
