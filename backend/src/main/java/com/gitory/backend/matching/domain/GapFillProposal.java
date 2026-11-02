package com.gitory.backend.matching.domain;

import java.util.UUID;

public record GapFillProposal(GapFillAction action, UUID targetId, String title, String repoName, UUID repoId,
                              String reason) {

    public GapFillProposal withTarget(String title, String repoName, UUID repoId) {

        return new GapFillProposal(action, targetId, title, repoName, repoId, reason);

    }
}
