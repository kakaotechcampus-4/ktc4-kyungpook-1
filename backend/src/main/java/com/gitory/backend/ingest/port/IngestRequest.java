package com.gitory.backend.ingest.port;

import java.util.List;

public record IngestRequest(Long userRepositoryId, List<String> branches, List<String> knownCommitShas) {

    public IngestRequest {
        branches = branches == null ? List.of() : List.copyOf(branches);
        knownCommitShas = knownCommitShas == null ? List.of() : List.copyOf(knownCommitShas);
    }
}
