package com.gitory.backend.agent.port;

import java.util.List;

public record ExperienceGroupingResponse(GroupingVerdict verdict, List<ExperienceCandidate> candidates,
                                         List<String> excludedCommitShas) {
}
