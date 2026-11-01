package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "활동을 만들거나 고칠 때 보내는 값. 고칠 때는 키가 없거나 null 인 값을 그대로 둡니다.")
public record ActivityRequest(
        @Schema(description = "공백뿐이면 안 되고 50자까지") String name,
        @Schema(description = "CLUB · EXTERNAL · CONTEST · INTERNSHIP · EDUCATION · PART_TIME · VOLUNTEER · STUDY · OTHER") String type,
        @Schema(description = "시작한 달, YYYY-MM", example = "2025-03") String startMonth,
        @Schema(description = "끝난 달, YYYY-MM. 만들 때 null·빈 글이면 진행 중이고, 고칠 때 빈 글이면 진행 중으로 바꿈", example = "2025-12") String endMonth,
        @Schema(description = "맡은 역할 50자까지. 빈 글이면 역할 없음") String role) {
}
