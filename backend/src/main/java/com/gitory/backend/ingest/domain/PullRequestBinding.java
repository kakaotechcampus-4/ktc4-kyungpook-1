package com.gitory.backend.ingest.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 내 커밋을 그 커밋의 작업인 PR 하나에 묶어, 경험 그룹화 재료와 분석에 쓴 커밋·PR 목록이 같은 규칙을 쓰게 한다 */
final class PullRequestBinding {

    private final Map<String, List<PullRequest>> pullRequestsBySha = new HashMap<>();
    private final Set<Integer> ownPullRequests;
    private final String defaultBranch;

    PullRequestBinding(List<PullRequest> pullRequests, String login, String defaultBranch) {
        this.defaultBranch = defaultBranch;
        for (PullRequest pullRequest : pullRequests) {
            for (String sha : pullRequest.getCommitShas()) {
                pullRequestsBySha.computeIfAbsent(sha, ignored -> new ArrayList<>()).add(pullRequest);
            }
        }
        this.ownPullRequests = pullRequests.stream()
                .filter(pullRequest -> login.equalsIgnoreCase(pullRequest.getAuthorLogin()))
                .filter(pullRequest -> !isRelease(pullRequest))
                .map(PullRequest::getGithubPrNumber)
                .collect(Collectors.toSet());
    }

    /** references 는 커밋 메시지에서 뽑은 '#숫자' 전부라 이슈 번호와 PR 번호가 섞여 있고, 묶을 PR 이 없으면 null 이다 */
    Integer pullRequestOf(String sha, List<Integer> references) {

        Integer narrowest = narrowestPullRequest(pullRequestsBySha.getOrDefault(sha, List.of()));
        if (narrowest != null) {
            return narrowest;
        }
        // 스쿼시로 머지한 커밋은 PR 커밋 목록에 없고 메시지에 PR 번호만 남는다
        // 남의 PR 번호를 적은 커밋이 남의 PR 후보가 되지 않게 내 PR 만 본다
        return references.stream().filter(ownPullRequests::contains).findFirst().orElse(null);

    }

    /** 한 커밋이 기능 PR 과 그 PR 을 덮는 넓은 PR 에 함께 들므로, 커밋이 가장 적은 PR 을 그 커밋의 작업으로 본다 */
    private Integer narrowestPullRequest(List<PullRequest> containing) {

        return containing.stream()
                .filter(pullRequest -> !isRelease(pullRequest))
                .min(Comparator.comparingInt((PullRequest pullRequest) -> pullRequest.getCommitShas().size())
                        .thenComparingInt(PullRequest::getGithubPrNumber))
                .map(PullRequest::getGithubPrNumber)
                .orElse(null);

    }

    /**
     * 기본 브랜치를 다른 브랜치로 올리는 릴리스 PR 은 그 사이 모든 커밋을 담아, 고르면 남이 만든 릴리스 PR 이 내 후보가 된다
     * 포크의 기본 브랜치에서 온 PR 은 head·base 이름이 같아 릴리스 PR 로 보지 않는다
     */
    private boolean isRelease(PullRequest pullRequest) {

        return pullRequest.getHeadBranch().equals(defaultBranch) && !pullRequest.getBaseBranch().equals(defaultBranch);

    }
}
