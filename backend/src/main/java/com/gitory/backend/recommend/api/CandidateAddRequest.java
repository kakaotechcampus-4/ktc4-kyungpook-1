package com.gitory.backend.recommend.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "직접 추가할 후보")
public record CandidateAddRequest(
        @Schema(description = "공백뿐이면 안 되고 200자까지. 받은 그대로 저장") String title,
        @Schema(description = "공백뿐이면 안 되고 300자까지. 받은 그대로 후보의 이유가 됩니다") String summary,
        @Schema(description = "붙일 내 커밋의 전체 sha. 없으면 커밋 없는 후보이고 1000개까지") List<String> shas) {
}
