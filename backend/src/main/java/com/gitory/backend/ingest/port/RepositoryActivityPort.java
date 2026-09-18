package com.gitory.backend.ingest.port;

public interface RepositoryActivityPort {

    IngestResult collect(IngestRequest request);
}
