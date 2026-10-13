package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardGeneration;
import io.swagger.v3.oas.annotations.media.Schema;

public record CardGenerationResponse(
        @Schema(format = "uuid", description = "초안을 만드는 Job 의 id") String jobId,
        @Schema(description = "일부만 만들어졌으면 true") boolean partial) {

    static CardGenerationResponse from(CardGeneration generation) {

        return new CardGenerationResponse(generation.jobId().toString(), generation.partial());

    }
}
