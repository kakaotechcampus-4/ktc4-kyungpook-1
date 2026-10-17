package com.gitory.backend.ingest.port;

import java.util.List;

public record DiffEvidenceRequest(Long userRepositoryId, List<String> commitShas, Integer githubPrNumber,
                                  String title) {
}
