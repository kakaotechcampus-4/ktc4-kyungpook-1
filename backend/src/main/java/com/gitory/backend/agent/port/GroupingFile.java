package com.gitory.backend.agent.port;

public record GroupingFile(String path, GroupingFileStatus status, int additions, int deletions, String patch) {
}
