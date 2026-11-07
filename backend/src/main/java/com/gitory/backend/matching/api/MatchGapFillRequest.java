package com.gitory.backend.matching.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record MatchGapFillRequest(
        @Schema(description = "'찾지 않음' 으로 멈춘 조건 가운데 그래도 찾아볼 조건 id. 비었거나 없으면 처음부터 찾는다", nullable = true)
        List<UUID> conditionIds) {
}
