package com.gitory.backend.agent.port;

public record StarStep(int round, StarStepActor actor, StarStepKind kind, StarSlot slot, String code, String value,
                       StarMaterialKind material, String reason) {
}
