package com.gitory.backend.agent.port;

import java.util.List;

public record StarSlotDraft(StarSlotStatus status, String text, StarConfidence confidence,
                            List<StarEvidenceRef> evidence, StarEmptyReason emptyReason, String emptyReasonText,
                            String reviewReason) {
}
