package com.gitory.backend.coverletter.domain;

public record AssignStep(int seq, String actor, String kind, Integer questionSeq, Long cardId, String reason) {
}
