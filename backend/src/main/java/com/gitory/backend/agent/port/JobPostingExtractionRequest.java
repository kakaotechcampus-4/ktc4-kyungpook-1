package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobPostingExtractionRequest(String source, String externalPostingId, String jobTitle, String sourceUrl,
                                          String rawText, String previousContentHash,
                                          PostingContentStatus previousContentStatus) {
}
