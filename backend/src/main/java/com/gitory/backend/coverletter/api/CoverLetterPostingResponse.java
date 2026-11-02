package com.gitory.backend.coverletter.api;

import com.gitory.backend.coverletter.domain.CoverLetterView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "자소서를 시작한 공고")
public record CoverLetterPostingResponse(
        @Schema(description = "공고 상세의 공고 id", format = "uuid") String id,
        String company,
        @Schema(description = "공고 제목") String title) {

    static CoverLetterPostingResponse from(CoverLetterView.PostingRef posting) {
        return posting == null ? null : new CoverLetterPostingResponse(posting.id().toString(), posting.company(),
                posting.title());
    }
}
