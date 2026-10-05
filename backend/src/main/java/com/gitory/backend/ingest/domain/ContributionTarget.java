package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCountTarget;

public record ContributionTarget(Long userRepositoryId, Long githubRepoId, String ownerLogin, String name) {

    GithubCountTarget toCountTarget() {

        return new GithubCountTarget(githubRepoId, ownerLogin, name);

    }
}
