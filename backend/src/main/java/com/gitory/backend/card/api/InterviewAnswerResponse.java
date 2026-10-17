package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.interview.InterviewAnswer;
import io.swagger.v3.oas.annotations.media.Schema;

public record InterviewAnswerResponse(
        String text,
        @Schema(description = "직접 쓴 답이면 USER_STATED, 보기에서 고른 답이면 USER_SELECTED") EvidenceType source) {

    static InterviewAnswerResponse from(InterviewAnswer answer) {

        return new InterviewAnswerResponse(answer.text(), answer.source().evidenceType());

    }
}
