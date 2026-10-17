package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.DraftCommit;

import java.util.List;
import java.util.UUID;

public record CandidateDraftSource(UUID publicId, CandidateType type, Integer githubPrNumber, String sourceRef,
                                   List<DraftCommit> commits) {
}
