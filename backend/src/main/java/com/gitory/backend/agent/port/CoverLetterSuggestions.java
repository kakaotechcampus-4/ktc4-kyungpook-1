package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterSuggestions(List<Item> suggestions) {

    public record Item(String source, String id, String angle) {
    }
}
