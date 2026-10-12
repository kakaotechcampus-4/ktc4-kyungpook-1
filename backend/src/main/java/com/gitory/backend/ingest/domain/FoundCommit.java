package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.UUID;

public record FoundCommit(String sha, String message, Instant authoredAt, String url, UUID candidateId) {

    static FoundCommit of(GithubRepo repository, GitCommit commit, UUID candidateId) {

        String url = "https://github.com/" + repository.getOwnerLogin() + "/" + repository.getName()
                + "/commit/" + commit.getSha();

        return new FoundCommit(commit.getSha(), commit.getMessage(), commit.getAuthoredAt(), url, candidateId);

    }
}
