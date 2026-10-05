package com.gitory.backend.ingest.port;

public interface RepositoryActivityPort {

    CollectedActivity collect(IngestRequest request);
}
