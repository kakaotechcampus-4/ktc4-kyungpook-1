package com.gitory.backend.ingest.domain;

import java.util.List;

public record Disclosure(List<String> reads, List<String> skips, int estimatedSeconds) {

    static final int UNCOUNTED_SECONDS = 60;
    static final double BASE_SECONDS = 5;
    static final double SECONDS_PER_GITHUB_CALL = 0.2;
    static final int GITHUB_CALLS_PER_PULL_REQUEST = 3;

    private static final List<String> SKIPS = List.of("비공개 저장소", "기본 브랜치가 아닌 브랜치", "저장소 파일 전체 내용");

    public static Disclosure of(RepositorySummary repository, IngestLimits limits) {

        boolean counted = repository.countedAt() != null;
        List<String> reads = List.of(
                "기본 브랜치 커밋 기록 (다른 사람 커밋 포함, 최대 " + limits.maxCommits() + "개)",
                counted ? "카드에는 내 커밋 " + repository.ownCommitCount() + "개만 써요" : "카드에는 내 커밋만 써요",
                "최근 PR·이슈 " + limits.maxPullRequests() + "개씩과 리뷰의 제목·본문 앞부분",
                "카드로 고른 커밋의 코드 변경 부분");

        return new Disclosure(reads, SKIPS, counted ? estimatedSecondsOf(repository, limits) : UNCOUNTED_SECONDS);

    }

    private static int estimatedSecondsOf(RepositorySummary repository, IngestLimits limits) {

        int githubCalls = Math.min(repository.commitCount(), limits.maxCommits())
                + GITHUB_CALLS_PER_PULL_REQUEST * Math.min(repository.prCount(), limits.maxPullRequests());

        return (int) Math.round(BASE_SECONDS + SECONDS_PER_GITHUB_CALL * githubCalls);

    }
}
