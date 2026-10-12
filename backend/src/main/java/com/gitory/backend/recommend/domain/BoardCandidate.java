package com.gitory.backend.recommend.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BoardCandidate(
        UUID id,
        CandidateType type,
        CandidateStatus status,
        String ref,
        String title,
        String reason,
        int commitCount,
        int fileCount,
        int reviewCommentCount,
        Integer days,
        BigDecimal codeRatio,
        boolean weak,
        BigDecimal score,
        List<BoardCommit> commits,
        UUID usedByCardId) {
}
