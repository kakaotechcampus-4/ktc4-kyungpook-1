package com.gitory.backend.ingest.domain;

import java.util.List;

public record Disclosure(List<String> reads, List<String> skips, int estimatedSeconds) {

    static final int UNCOUNTED_SECONDS = 60;
    static final double BASE_SECONDS = 5;
    // 아래 계수는 서버에서 잰 분석 시간으로 맞춘 값이라 수집이나 경험 그룹화 방식이 바뀌면 다시 재서 고친다
    static final double SECONDS_PER_GITHUB_CALL = 0.4;
    static final int GITHUB_CALLS_PER_PULL_REQUEST = 3;
    // 경험 그룹화는 AI 에 내 커밋이 든 PR 만 보내므로, 분석 전에 알 수 있는 내가 만든 PR 수로 시간을 어림한다
    static final double GROUPING_BASE_SECONDS = 15;
    static final double GROUPING_SECONDS_PER_OWN_PULL_REQUEST = 2.7;

    private static final List<String> SKIPS = List.of("비공개 저장소", "기본 브랜치가 아닌 브랜치", "저장소 파일 전체 내용");

    public static Disclosure of(RepositorySummary repository, IngestLimits limits, boolean groupingEnabled) {

        boolean counted = repository.countedAt() != null;
        List<String> reads = List.of(
                "기본 브랜치 커밋 기록 (다른 사람 커밋 포함, 최대 " + limits.maxCommits() + "개)",
                counted ? "카드에는 내 커밋 " + repository.ownCommitCount() + "개만 써요" : "카드에는 내 커밋만 써요",
                "최근 PR " + limits.maxPullRequests() + "개·그보다 오래된 내 PR 최대 " + limits.maxOwnPullRequests()
                        + "개·최근 이슈 " + limits.maxIssues() + "개와 리뷰의 제목·본문 앞부분",
                "카드로 고른 커밋의 코드 변경 부분");

        return new Disclosure(reads, SKIPS,
                counted ? estimatedSecondsOf(repository, limits, groupingEnabled) : UNCOUNTED_SECONDS);

    }

    private static int estimatedSecondsOf(RepositorySummary repository, IngestLimits limits, boolean groupingEnabled) {

        int commits = Math.min(repository.commitCount(), limits.maxCommits());
        int recentPullRequests = Math.min(repository.prCount(), limits.maxPullRequests());
        int olderPullRequests = Math.max(repository.prCount() - limits.maxPullRequests(), 0);
        // 내 PR 이 모두 최근 상한 밖에 있다고 보고 가장 많이 읽는 경우로 어림한다
        int olderOwnPullRequests = Math.min(Math.min(repository.ownPrCount(), olderPullRequests),
                limits.maxOwnPullRequests());
        int pullRequests = recentPullRequests + olderOwnPullRequests;
        int githubCalls = commits + GITHUB_CALLS_PER_PULL_REQUEST * pullRequests;
        double seconds = BASE_SECONDS + SECONDS_PER_GITHUB_CALL * githubCalls;

        if (groupingEnabled) {
            int ownPullRequests = Math.min(repository.ownPrCount(),
                    limits.maxPullRequests() + limits.maxOwnPullRequests());

            seconds += GROUPING_BASE_SECONDS + GROUPING_SECONDS_PER_OWN_PULL_REQUEST * ownPullRequests;
        }

        return (int) Math.round(seconds);

    }
}
