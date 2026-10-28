package com.gitory.backend.card.domain;

import java.util.UUID;

public record ActivityReference(UUID publicId, String name, ActivityType type) {

    static ActivityReference of(Activity activity) {

        return new ActivityReference(activity.getPublicId(), activity.getName(), activity.getActivityType());

    }
}
