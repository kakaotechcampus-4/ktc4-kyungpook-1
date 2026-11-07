package com.gitory.backend.matching.domain;

import java.util.List;

public record GapFillView(GapFillStatus state, Integer pollAfterMs, List<GapFillCondition> conditions) {
}
