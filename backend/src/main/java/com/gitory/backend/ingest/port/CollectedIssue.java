package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.GithubState;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CollectedIssue(int issueNumber, String title, String bodyExcerpt, GithubState state, String authorLogin,
                             List<String> labels, Instant openedAt, Instant closedAt) {
}
