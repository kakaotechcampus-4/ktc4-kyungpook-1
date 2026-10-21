package com.gitory.backend.agent.port;

import java.util.List;
import java.util.Map;

public record StarDraft(String title, Map<StarSlot, StarSlotDraft> slots, List<StarSlot> missingFields,
                        String sharedWith, List<String> removedClaims, List<String> areas, List<String> techs) {
}
