package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "고칠 제목·기간·문항 유형. 키가 없거나 null 인 칸은 그대로 둡니다.")
public record CardMetadataRequest(
        @Schema(description = "공백뿐이면 안 되고 256자까지") String title,
        @Schema(description = "자유 글 50자까지이고, 빈 글이나 공백뿐이면 기간을 지움") String period,
        @Schema(description = "경험 카드의 자소서 문항 유형 0~2개. 빈 배열은 비우기이고, 확정 카드도 바꿀 수 있음") List<String> themes) {
}
