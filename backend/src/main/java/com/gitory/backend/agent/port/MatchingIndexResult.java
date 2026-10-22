package com.gitory.backend.agent.port;

public record MatchingIndexResult(int indexedPostings, int indexedCriteria, int deactivatedPostings) {
}
