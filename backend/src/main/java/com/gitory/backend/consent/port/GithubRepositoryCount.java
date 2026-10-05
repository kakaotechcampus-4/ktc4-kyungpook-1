package com.gitory.backend.consent.port;

public record GithubRepositoryCount(long githubRepoId, int commitCount, int ownCommitCount,
                                    int pullRequestCount, int ownPullRequestCount, int reviewedPullRequestCount) {
}
