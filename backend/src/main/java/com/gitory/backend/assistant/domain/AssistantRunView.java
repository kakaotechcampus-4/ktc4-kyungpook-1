package com.gitory.backend.assistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssistantRunView(UUID id, AssistantRunState state, int stepsUsed, int stepLimit, String errorCode,
                               List<RunItem> items, Instant createdAt, Instant updatedAt) {

    static AssistantRunView of(AssistantRun run) {

        return new AssistantRunView(run.getPublicId(), run.getState(), run.getStepsUsed(), run.getStepLimit(),
                run.getErrorCode(), List.copyOf(run.getItems()), run.getCreatedAt(), run.getUpdatedAt());

    }
}
