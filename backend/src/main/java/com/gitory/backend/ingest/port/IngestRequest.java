package com.gitory.backend.ingest.port;

import java.util.List;

/**
 * @param branches 수집할 브랜치. 비어 있으면 기본 브랜치만 읽게 되어 기여가 사라진다.
 */
public record IngestRequest(String owner, String name, List<String> branches) {
}
