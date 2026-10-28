package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "자소서를 만들 때 보내는 값")
public record CreateCoverLetterRequest(
        @Schema(description = "지원 직무, 1~100자", example = "백엔드 개발") String jobRole,
        @Schema(description = "지원 이유 등 메모, 1,000자까지. 없으면 null") String memo,
        @Schema(description = "문항 1~10개") List<Question> questions) {

    @Schema(name = "CoverLetterQuestionInput")
    public record Question(
            @Schema(description = "회사가 묻는 문항 문장, 1~500자") String text,
            @Schema(description = "공백 포함 글자 수 100~5,000. null 이면 700") Integer charLimit) {
    }
}
