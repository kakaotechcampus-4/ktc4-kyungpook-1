package com.gitory.backend.recommend.domain;

import java.util.List;

public record GroupedCandidate(CandidateType sourceType, String sourceRef, Integer pullRequestNumber,
                               List<Integer> pullRequestNumbers, List<Integer> issueNumbers, String title,
                               String reason, double score, boolean lowCardWorth, List<String> commitShas) {
}
