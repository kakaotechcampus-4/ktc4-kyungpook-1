package com.gitory.backend.consent.port;

import java.util.List;

public record DiffEvidenceResult(List<DiffEvidence> evidence, boolean partial, DiffEvidencePartialReason partialReason,
                                 List<String> missingCommitShas) {
}
