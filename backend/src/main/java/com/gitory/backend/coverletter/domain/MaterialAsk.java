package com.gitory.backend.coverletter.domain;

import java.util.List;

public record MaterialAsk(MaterialItem item, String question, boolean required, List<String> templates) {

    public MaterialAsk {
        templates = templates == null ? List.of() : List.copyOf(templates);
    }
}
