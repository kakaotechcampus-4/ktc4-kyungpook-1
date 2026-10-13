package com.gitory.backend.recommend.domain;

public record CandidateForCard(Long id, String title, boolean hasIncludedCommit, String taskText) {
}
