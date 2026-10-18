package com.gitory.backend.agent.port;

public record InterviewFollowUp(InterviewStatementTarget target, InterviewQuestionType questionType,
                                String questionText) {
}
