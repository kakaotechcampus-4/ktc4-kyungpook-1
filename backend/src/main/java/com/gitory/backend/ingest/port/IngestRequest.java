package com.gitory.backend.ingest.port;

import java.util.List;

public record IngestRequest(String owner, String name, List<String> branches) {
}
