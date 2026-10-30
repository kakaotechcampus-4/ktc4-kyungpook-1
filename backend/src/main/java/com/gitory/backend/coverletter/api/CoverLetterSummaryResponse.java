package com.gitory.backend.coverletter.api;

import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "자소서 목록의 한 줄")
public record CoverLetterSummaryResponse(
        @Schema(format = "uuid") String id,
        CoverLetterStage stage,
        String jobRole,
        int questionCount,
        String firstQuestion,
        Instant createdAt,
        Instant updatedAt) {

    static CoverLetterSummaryResponse from(CoverLetterSummary letter) {
        return new CoverLetterSummaryResponse(letter.id().toString(), letter.stage(), letter.jobRole(),
                letter.questionCount(), letter.firstQuestion(), letter.createdAt(), letter.updatedAt());
    }
}
