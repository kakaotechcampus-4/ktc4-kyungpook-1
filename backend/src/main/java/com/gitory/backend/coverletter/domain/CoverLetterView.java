package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CoverLetterView(UUID id, CoverLetterStage stage, CoverLetterStep failedStep, String errorCode,
                              Boolean retryable, boolean running, String jobRole, String memo, Instant createdAt,
                              Instant updatedAt, List<Question> questions, List<Step> assignSteps) {

    public record Question(UUID id, int seq, String text, int charLimit, QuestionTheme theme, CardRef primaryCard,
                           CardRef supportingCard, AssignedBy assignedBy, String pickReason,
                           List<Considered> considered, List<MaterialItem> materials, List<Ask> asks) {
    }

    public record Ask(MaterialItem item, String question, boolean required, Answer answer, boolean skipped) {
    }

    public record Answer(String text, boolean reused) {
    }

    public record CardRef(UUID id, String title, CardKind kind) {
    }

    public record Considered(UUID cardId, String title, String note) {
    }

    public record Step(int seq, String actor, String kind, UUID questionId, UUID cardId, String reason) {
    }
}
