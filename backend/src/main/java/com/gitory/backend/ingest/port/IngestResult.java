package com.gitory.backend.ingest.port;

public record IngestResult(int commits, int pullRequests, int issues,
                           boolean partial, String partialReason) {
}
