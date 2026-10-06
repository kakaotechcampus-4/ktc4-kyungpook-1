package com.gitory.backend.ingest.domain;

import java.time.Instant;

public record FoundCommit(String sha, String message, Instant authoredAt, String url) {

    static FoundCommit of(GithubRepo repository, GitCommit commit) {

        String url = "https://github.com/" + repository.getOwnerLogin() + "/" + repository.getName()
                + "/commit/" + commit.getSha();

        return new FoundCommit(commit.getSha(), commit.getMessage(), commit.getAuthoredAt(), url);

    }
}
