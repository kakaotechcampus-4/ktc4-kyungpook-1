package com.gitory.backend.recommend.domain;

import java.util.UUID;

public record CommitCandidate(Long commitId, UUID candidatePublicId) {
}
