package com.gitory.backend.agent.port;

import java.util.List;

public record ExperienceCandidate(String groupKey, ExperienceSourceType sourceType, String sourceRef,
                                  Integer pullRequestNumber, List<Integer> pullRequestNumbers,
                                  List<Integer> issueNumbers, String title, String reason, double score,
                                  boolean lowCardWorth, List<String> commitShas) {
}
