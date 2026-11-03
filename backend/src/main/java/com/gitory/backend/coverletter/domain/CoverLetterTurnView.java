package com.gitory.backend.coverletter.domain;

import java.time.Instant;
import java.util.UUID;

public record CoverLetterTurnView(int seq, TurnRole role, UUID questionId, String text, MaterialItem item,
                                  TurnStatus status, Instant createdAt) {
}
