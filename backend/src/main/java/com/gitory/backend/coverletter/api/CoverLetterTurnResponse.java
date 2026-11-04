package com.gitory.backend.coverletter.api;

import com.gitory.backend.coverletter.domain.CoverLetterTalk;
import com.gitory.backend.coverletter.domain.CoverLetterTurnView;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "대화 한 번 뒤의 대화 전체와 그 문항")
public record CoverLetterTurnResponse(
        List<CoverLetterTurnView> turns,
        @Schema(description = "말한 문항으로, 답으로 다시 썼으면 바뀐 초안이 들어 있다") CoverLetterView.Question question,
        @Schema(description = "답은 저장했지만 그 답으로 글을 다시 쓰지 못해 글이 그대로면 true") boolean rewriteFailed) {

    static CoverLetterTurnResponse from(CoverLetterTalk talk) {
        return new CoverLetterTurnResponse(talk.turns(), talk.question(), talk.rewriteFailed());
    }
}
