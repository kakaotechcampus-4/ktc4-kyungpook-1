package com.gitory.backend.agent.port;

public record InterviewQuestionResult(InterviewNextAction nextAction, InterviewStarSlot targetStarSlot,
                                      Integer targetStatementSeq, InterviewQuestionType questionType,
                                      String questionText) {
}
