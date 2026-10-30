package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.DraftMaterial;
import com.gitory.backend.card.domain.DraftStep;
import com.gitory.backend.card.domain.DraftStepActor;
import com.gitory.backend.card.domain.DraftStepKind;
import com.gitory.backend.card.domain.StarField;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 초안이 거친 단계 하나로, 화면이 값으로 문장을 만든다")
public record DraftStepResponse(
        @Schema(description = "0 은 첫 초안과 첫 검사, 1 부터는 고쳐 쓴 바퀴(최대 5)") int round,
        @Schema(description = "MODEL 은 AI 가, CODE 는 코드가 한 단계") DraftStepActor actor,
        @Schema(description = "DRAFT 첫 초안·FLAG 문제 발견·OPEN 자료 열기로 고름·RETRY 자료 없이 고쳐 쓰기로 고름·KEEP 그대로 두기로 고름·REWRITE 칸을 다시 씀·PASS 다시 검사 통과·REVERT 다시 쓴 칸이 나빠져 앞 글로 되돌림") DraftStepKind kind,
        @Schema(description = "단계가 다룬 칸이고, 카드 전체 단계(DRAFT)는 null", nullable = true) StarField field,
        @Schema(description = "FLAG 의 이유 코드(NO_EVIDENCE·UNSOURCED_NUMBER·REVERSED_MEANING·CODE_NAME·EMPTY 등)와 REVERT 하게 만든 문제 코드이고, 그 밖에는 null", nullable = true) String code,
        @Schema(description = "걸린 수치나 연 자료의 제목이고, 없으면 null", nullable = true) String value,
        @Schema(description = "OPEN 으로 연 자료의 종류이고, 그 밖에는 null", nullable = true) DraftMaterial material,
        @Schema(description = "AI 가 고른 행동(OPEN·RETRY·KEEP)의 이유 한 줄(60자 이내)이고, 코드가 정한 행동이면 null", nullable = true) String reason) {

    static DraftStepResponse from(DraftStep step) {

        return new DraftStepResponse(step.round(), step.actor(), step.kind(), step.field(), step.code(), step.value(),
                step.material(), step.reason());

    }
}
