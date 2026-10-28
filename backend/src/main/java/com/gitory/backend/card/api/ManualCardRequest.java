package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "직접 작성 카드를 시작할 때 보내는 제목·기간과, 카드를 묶을 저장소나 활동")
public record ManualCardRequest(
        @Schema(description = "공백뿐이면 안 되고 256자까지") String title,
        @Schema(description = "자유 글 50자까지이고, 빈 글이나 공백뿐이면 기간 없음으로 저장") String period,
        @Schema(format = "uuid", description = "저장소 목록의 id 이고, 관련 저장소가 없으면 null") String repoId,
        @Schema(format = "uuid", description = "활동 목록의 id 이고, 활동 밖 카드면 null. repoId 와 함께 보내면 400") String activityId) {
}
