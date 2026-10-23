package com.gitory.backend.agent.port;

import java.util.List;

public record MatchingSearchRequest(List<MatchingStatement> statements) {
}
