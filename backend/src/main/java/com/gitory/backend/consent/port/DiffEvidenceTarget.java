package com.gitory.backend.consent.port;

import java.util.List;

public record DiffEvidenceTarget(Long userRepositoryId, GithubCollectionRepo repository, List<String> commitShas,
                                 Integer githubPrNumber, String title) {
}
