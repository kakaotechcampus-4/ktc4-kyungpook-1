package com.gitory.backend.card.domain;

public record DraftStep(int round, DraftStepActor actor, DraftStepKind kind, StarField field, String code,
                        String value, DraftMaterial material, String reason) {
}
