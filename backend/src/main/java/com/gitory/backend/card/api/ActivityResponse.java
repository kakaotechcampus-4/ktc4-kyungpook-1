package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.ActivityType;
import com.gitory.backend.card.domain.ActivityView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "레포 밖 경험(동아리·대외활동 등)을 묶는 활동")
public record ActivityResponse(
        @Schema(format = "uuid") String id,
        String name,
        ActivityType type,
        @Schema(description = "YYYY-MM", example = "2025-03") String startMonth,
        @Schema(description = "YYYY-MM 이고, null 이면 진행 중", example = "2025-12") String endMonth,
        String role,
        @Schema(description = "이 활동 안의 카드 수로, 지우기 전에 함께 지워질 카드 수를 보여 줄 때 쓴다") long cardCount,
        Instant createdAt) {

    static ActivityResponse from(ActivityView activity) {

        return new ActivityResponse(activity.publicId().toString(), activity.name(), activity.type(),
                activity.startMonth().toString(),
                activity.endMonth() == null ? null : activity.endMonth().toString(),
                activity.role(), activity.cardCount(), activity.createdAt());

    }
}
