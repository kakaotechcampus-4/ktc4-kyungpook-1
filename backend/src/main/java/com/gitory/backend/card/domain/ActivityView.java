package com.gitory.backend.card.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

public record ActivityView(
        UUID publicId,
        String name,
        ActivityType type,
        YearMonth startMonth,
        YearMonth endMonth,
        String role,
        long cardCount,
        Instant createdAt) {

    static ActivityView of(Activity activity, long cardCount) {

        return new ActivityView(activity.getPublicId(), activity.getName(), activity.getActivityType(),
                YearMonth.from(activity.getStartMonth()),
                activity.getEndMonth() == null ? null : YearMonth.from(activity.getEndMonth()),
                activity.getRole(), cardCount, activity.getCreatedAt());

    }
}
