package com.gitory.backend.recommend.domain;

import java.util.List;
import java.util.UUID;

public record CandidateBoard(
        UUID repositoryId,
        CandidateVerdict verdict,
        List<String> emptyReasons,
        List<BoardCandidate> candidates) {
}
