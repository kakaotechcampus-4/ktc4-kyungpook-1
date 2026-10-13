package com.gitory.backend.recommend.domain;

import java.util.List;
import java.util.UUID;

public record Recall(UUID repositoryId, String note, List<RecallDir> dirs) {
}
