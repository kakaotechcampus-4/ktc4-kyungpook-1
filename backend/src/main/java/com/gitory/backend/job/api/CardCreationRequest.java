package com.gitory.backend.job.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "카드로 만들 후보")
public record CardCreationRequest(
        @Schema(description = "후보 보드의 후보 id 이고 1개 이상, 같은 id 를 여러 번 보내면 한 번으로 셈") List<String> candidateIds) {
}
