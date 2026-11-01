package com.gitory.backend.ingest.domain;

import java.util.List;

public record PullRequestMaterials(List<LinkedIssue> issues, List<String> reviews, List<String> ownerReplies) {

    public record LinkedIssue(int number, String title, String body) {
    }

    public boolean isEmpty() {

        return issues.isEmpty() && reviews.isEmpty() && ownerReplies.isEmpty();

    }
}
