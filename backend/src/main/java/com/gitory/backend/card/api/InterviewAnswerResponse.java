package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.interview.InterviewAnswer;
import io.swagger.v3.oas.annotations.media.Schema;

public record InterviewAnswerResponse(
        String text,
        @Schema(description = "직접 쓴 답이면 USER_STATED, 보기에서 고른 답이면 USER_SELECTED") EvidenceType source,
        @Schema(description = "다듬기 미리 보기를 거쳐 저장한 답의 다듬기 전 사용자 답으로, 다듬지 않고 저장했으면 null", nullable = true)
        String original) {

    static InterviewAnswerResponse from(InterviewAnswer answer) {

        return new InterviewAnswerResponse(answer.text(), answer.source().evidenceType(), answer.original());

    }
}
