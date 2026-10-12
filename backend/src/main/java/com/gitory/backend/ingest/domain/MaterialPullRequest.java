package com.gitory.backend.ingest.domain;

import java.util.List;

public record MaterialPullRequest(int number, String title, String bodyExcerpt, List<Integer> linkedIssueNumbers) {
}
