package com.gitory.backend.recommend.domain;

import java.util.Set;

public record LockedSources(Set<String> commitShas, Set<Integer> pullRequestNumbers) {
}
