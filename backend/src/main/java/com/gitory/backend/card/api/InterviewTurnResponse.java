package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.InterviewSourceType;
import com.gitory.backend.card.domain.interview.InterviewTurnView;
import com.gitory.backend.card.domain.interview.QuestionType;
import com.gitory.backend.card.domain.interview.TriggerSource;
import com.gitory.backend.card.domain.interview.TurnOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "되묻기 질문 하나와 그 답으로, 질문 근거(found·missing)는 아직 만들지 않아 늘 빈 배열이다")
public record InterviewTurnResponse(
        @Schema(description = "카드 안의 질문 번호") int turnNo,
        @Schema(description = "질문이 묻는 칸") StarField field,
        AskedBy askedBy,
        InterviewSourceType sourceType,
        QuestionType questionType,
        List<String> found,
        List<String> missing,
        String question,
        @Schema(description = "AI 가 쓴 예상 답변으로, 그대로 고르면 USER_SELECTED 다") List<String> options,
        @Schema(description = "보일 답 버튼으로, MEASURE 는 R 수치 질문이다") AnswerMode answerMode,
        @Schema(description = "질문이 어떻게 끝났는지로, 답 전이면 null 이다", nullable = true) TurnOutcome outcome,
        @Schema(description = "답하지 않은 질문이면 null") InterviewAnswerResponse answer,
        @Schema(description = "이 질문을 만든 뒤 남은 질문 수") int remaining,
        @Schema(description = "카드 하나에 만들 수 있는 질문의 최대 수") int maxTurns) {

    static InterviewTurnResponse from(InterviewTurnView turn) {

        InterviewAnswerResponse answer = turn.answer() == null ? null : InterviewAnswerResponse.from(turn.answer());

        return new InterviewTurnResponse(turn.turnNo(), turn.field(), askedByOf(turn.triggerSource()),
                turn.sourceType(), turn.questionType(), List.of(), List.of(), turn.question(), turn.options(),
                turn.answerMode(), turn.outcome(), answer, turn.remaining(), turn.maxTurns());

    }

    private static AskedBy askedByOf(TriggerSource source) {

        return switch (source) {
            case USER -> AskedBy.USER_REQUEST;
            case AUTO -> AskedBy.FOLLOW_UP;
        };

    }
}
