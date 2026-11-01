package com.gitory.backend.ingest.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.infra.GroupingMaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 분석이 끝난 뒤 '읽은 커밋·PR 전체 보기' 에 보여 줄 분석에 쓴 내 커밋·뺀 내 커밋과, 그 커밋이 묶인 PR 을 모은다
 * 손댄 후보라 이번 경험 그룹화에서 빠진 커밋도 전에 분석에 쓴 재료라 함께 보여 준다
 */
@Service
@RequiredArgsConstructor
public class RepositoryMaterialService {

    private static final int SHORT_SHA_LENGTH = 7;

    private final RepositoryOwnershipService ownership;
    private final GroupingMaterialRepository materials;

    @Transactional(readOnly = true)
    public RepositoryMaterials materials(UUID publicId, Long userId, String login) {

        Long connectionId = ownership.findOwnedRepository(publicId, userId, AuditAction.VIEW_REPO_MATERIALS)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);
        GithubRepo repository = materials.findRepository(connectionId).orElseThrow();
        List<PullRequest> pullRequests = materials.findPullRequests(repository.getId());
        PullRequestBinding binding = new PullRequestBinding(pullRequests, login, repository.getDefaultBranch());

        List<RepositoryMaterials.CommitLine> commits = new ArrayList<>();
        for (GitCommit commit : materials.findOwnCommits(repository.getId(), login)) {
            Integer pullRequestNumber = binding.pullRequestOf(commit.getSha().stripTrailing(),
                    Objects.requireNonNullElse(commit.getIssueNumbers(), List.of()));
            commits.add(lineOf(commit, pullRequestNumber));
        }
        materials.findOwnExcludedCommits(repository.getId(), login).forEach(commit -> commits.add(lineOf(commit, null)));
        commits.sort(Comparator.comparing(RepositoryMaterials.CommitLine::authoredAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RepositoryMaterials.CommitLine::sha));

        Map<Integer, Long> commitCounts = commits.stream()
                .map(RepositoryMaterials.CommitLine::pullRequestNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<RepositoryMaterials.PullRequestLine> pullRequestLines = pullRequests.stream()
                .filter(pullRequest -> commitCounts.containsKey(pullRequest.getGithubPrNumber()))
                .sorted(Comparator.comparingInt(PullRequest::getGithubPrNumber).reversed())
                .map(pullRequest -> new RepositoryMaterials.PullRequestLine(pullRequest.getGithubPrNumber(),
                        pullRequest.getTitle(), pullRequest.getMergedAt(),
                        commitCounts.get(pullRequest.getGithubPrNumber()).intValue()))
                .toList();

        return new RepositoryMaterials(commits, pullRequestLines);

    }

    private static RepositoryMaterials.CommitLine lineOf(GitCommit commit, Integer pullRequestNumber) {

        String sha = commit.getSha().stripTrailing();
        String title = Objects.requireNonNullElse(commit.getMessage(), "").strip().lines().findFirst().orElse("");

        return new RepositoryMaterials.CommitLine(sha.substring(0, Math.min(SHORT_SHA_LENGTH, sha.length())), title,
                commit.getAuthoredAt(), Objects.requireNonNullElse(commit.getChangedFiles(), 0), pullRequestNumber,
                commit.getExclusionReason());

    }
}
