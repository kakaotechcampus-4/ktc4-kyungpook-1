package com.gitory.backend.card.api;

import com.gitory.backend.recommend.domain.CandidateReference;
import com.gitory.backend.recommend.domain.CandidateType;
import io.swagger.v3.oas.annotations.media.Schema;

public record CardCandidateResponse(
        @Schema(format = "uuid") String id,
        CandidateType type,
        @Schema(description = "후보 보드에 보이는 PR 번호나 날짜 같은 참조 글자이고, AI 가 주지 않았으면 빈 글자") String ref,
        String title) {

    static CardCandidateResponse from(CandidateReference candidate) {

        return new CardCandidateResponse(candidate.publicId().toString(), candidate.type(), candidate.ref(),
                candidate.title());

    }
}
