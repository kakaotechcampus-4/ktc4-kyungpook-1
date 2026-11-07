package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "자소서를 만들 때 보내는 값")
public record CreateCoverLetterRequest(
        @Schema(description = "지원 직무, 1~100자", example = "백엔드 개발") String jobRole,
        @Schema(description = "지원 이유, 선택. 없으면 null 이고, 적으면 공백 뺀 20자 이상 1,000자까지이고 한글이 있어야 하며, 같은 글자(또는 2~3자 조각) 5번 이상 이어 쓰기·URL·이메일·한글/영문/숫자/문장부호 밖의 글자는 받지 않는다") String memo,
        @Schema(description = "강점 코드 0~3개, 중복 없음. 없으면 null 또는 빈 배열") List<String> strengths,
        @Schema(description = "문항 1~10개") List<Question> questions,
        @Schema(description = "공고 상세에서 시작한 자소서면 그 공고 id, 아니면 null", format = "uuid") String postingId) {

    @Schema(name = "CoverLetterQuestionInput")
    public record Question(
            @Schema(description = "회사가 묻는 문항 문장, 1~500자") String text,
            @Schema(description = "공백 포함 글자 수 100~5,000. null 이면 700") Integer charLimit) {
    }
}
