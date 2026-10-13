package com.gitory.backend.recommend.domain;

import java.util.List;

public record RecallDir(
        String path,
        int filesChanged,
        int myCommits,
        List<String> files,
        String question,
        List<String> options) {
}
