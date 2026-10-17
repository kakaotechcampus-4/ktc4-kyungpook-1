package com.gitory.backend.recommend.domain;

import java.util.UUID;

public record EditedCandidate(UUID repositoryId, BoardCandidate candidate) {
}
