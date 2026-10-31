package com.gitory.backend.card.domain;

import java.util.List;

public record PolishedDraft(String sentence, boolean polished, List<String> unsourcedNumbers) {

    static PolishedDraft unpolished(String answer) {

        return new PolishedDraft(answer, false, List.of());

    }
}
