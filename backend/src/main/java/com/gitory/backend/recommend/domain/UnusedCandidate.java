package com.gitory.backend.recommend.domain;

import java.util.List;
import java.util.UUID;

public record UnusedCandidate(Long userRepositoryId, UUID publicId, String title, String reason,
                              List<String> commitMessages) {
}
