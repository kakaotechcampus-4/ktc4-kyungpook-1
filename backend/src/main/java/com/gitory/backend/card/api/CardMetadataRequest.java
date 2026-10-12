package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "고칠 제목·기간. 키가 없거나 null 인 칸은 그대로 둡니다.")
public record CardMetadataRequest(
        @Schema(description = "공백뿐이면 안 되고 256자까지") String title,
        @Schema(description = "자유 글 50자까지이고, 빈 글이나 공백뿐이면 기간을 지움") String period) {
}
