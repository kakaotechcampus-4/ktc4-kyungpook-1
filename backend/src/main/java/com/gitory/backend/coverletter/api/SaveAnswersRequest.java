package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "문항의 답 전체로, 보내지 않은 항목은 답이 없는 것으로 남는다")
public record SaveAnswersRequest(List<Answer> answers) {

    @Schema(name = "CoverLetterAnswerInput", description = "질문 하나의 답")
    public record Answer(
            @Schema(description = "그 문항 asks 에 있는 항목", allowableValues = {"SITUATION", "ACTION", "RESULT", "LEARNING",
                    "TRIGGER", "JOB_LINK", "REASON"}) String item,
            @Schema(description = "넘기지 않았으면 1~1,000자, 넘겼으면 보지 않는다", nullable = true) String text,
            @Schema(description = "넘기기를 눌렀으면 true. 없으면 false 로 본다", nullable = true) Boolean skipped) {
    }
}
