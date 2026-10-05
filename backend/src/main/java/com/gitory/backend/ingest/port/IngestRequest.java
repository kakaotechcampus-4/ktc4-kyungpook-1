package com.gitory.backend.ingest.port;

import java.time.Instant;
import java.util.List;

public record IngestRequest(Long userRepositoryId, List<String> branches, Instant since) {
}
