package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.GithubState;

import java.time.Instant;
import java.util.List;

public record CollectedPullRequest(int number, String title, String bodyExcerpt, GithubState state, String authorLogin,
                                   String baseBranch, String headBranch, Instant openedAt, Instant mergedAt,
                                   List<String> commitShas, List<Integer> linkedIssueNumbers) {
}
