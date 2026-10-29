package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.ActivityReference;
import com.gitory.backend.card.domain.ActivityType;
import io.swagger.v3.oas.annotations.media.Schema;

public record CardActivityResponse(
        @Schema(format = "uuid") String id,
        String name,
        ActivityType type) {

    static CardActivityResponse from(ActivityReference activity) {

        return new CardActivityResponse(activity.publicId().toString(), activity.name(), activity.type());

    }
}
