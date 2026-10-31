package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.DraftStep;
import com.gitory.backend.card.domain.StarField;

import java.util.List;
import java.util.UUID;

public record CardDraftLive(UUID cardId, String title, CardDraftLiveState state, List<Slot> slots,
                            List<DraftStep> steps) {

    public record Slot(StarField field, int round, String text, boolean done) {
    }
}
