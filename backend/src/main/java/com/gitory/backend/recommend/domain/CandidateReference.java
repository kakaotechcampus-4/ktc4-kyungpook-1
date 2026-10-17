package com.gitory.backend.recommend.domain;

import java.util.UUID;

public record CandidateReference(Long id, UUID publicId, CandidateType type, String ref, String title) {
}
