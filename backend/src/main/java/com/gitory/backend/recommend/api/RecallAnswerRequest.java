package com.gitory.backend.recommend.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회상 도우미에서 한 폴더에 쓴 답")
public record RecallAnswerRequest(
        @Schema(description = "목록에서 받은 폴더 경로. 비어 있으면 안 되고 197자까지") String path,
        @Schema(description = "답. 공백뿐이면 안 되고 300자까지이며 받은 그대로 후보의 이유가 됩니다") String text) {
}
