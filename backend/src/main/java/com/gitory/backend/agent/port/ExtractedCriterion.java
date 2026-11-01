package com.gitory.backend.agent.port;

import java.util.List;

public record ExtractedCriterion(PostingCriterionType type, String text, String quote, PostingGeneralType generalType,
                                 List<String> techs, PostingEducationLevel educationLevel, boolean educationExempt) {

    public ExtractedCriterion(PostingCriterionType type, String text, String quote) {
        this(type, text, quote, null, List.of(), null, false);
    }
}
