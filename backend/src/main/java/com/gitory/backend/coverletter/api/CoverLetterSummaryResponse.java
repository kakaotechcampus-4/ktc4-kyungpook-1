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
        @Schema(description = "공고 상세에서 시작한 자소서면 그 공고, 아니면 null") CoverLetterPostingResponse posting,
        int questionCount,
        @Schema(description = "초안이 있지만 빈칸 틀만 남아 글이 없는 문항 수") int emptyQuestionCount,
        String firstQuestion,
        Instant createdAt,
        Instant updatedAt) {

    static CoverLetterSummaryResponse from(CoverLetterSummary letter) {
        return new CoverLetterSummaryResponse(letter.id().toString(), letter.stage(), letter.jobRole(),
                CoverLetterPostingResponse.from(letter.posting()), letter.questionCount(),
                letter.emptyQuestionCount(), letter.firstQuestion(), letter.createdAt(), letter.updatedAt());
    }
}
