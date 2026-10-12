package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.MaskRule;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "가릴 말(from)을 바꿀 말(to)로 보여 주는 규칙 목록으로, 받은 순서 그대로 저장한다")
public record CardMaskRequest(
        @Schema(description = "20개까지이고, 빈 목록이면 규칙을 모두 지운다") List<MaskRule> rules) {
}
