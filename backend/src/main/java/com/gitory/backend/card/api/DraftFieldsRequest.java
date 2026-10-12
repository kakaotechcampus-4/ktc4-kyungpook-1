package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "임시 저장할 네 칸. 네 키를 모두 보내야 하고, null·빈 글·공백뿐인 칸은 비운 칸입니다.")
public record DraftFieldsRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String situation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String task,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String action,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String result) {
}
