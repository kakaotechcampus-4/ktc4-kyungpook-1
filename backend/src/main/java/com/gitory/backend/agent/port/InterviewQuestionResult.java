package com.gitory.backend.agent.port;

import java.util.List;

public record InterviewQuestionResult(InterviewNextAction nextAction, InterviewStarSlot targetStarSlot,
                                      Integer targetStatementSeq, InterviewQuestionType questionType,
                                      String questionText, InterviewAnswerMode answerMode,
                                      List<String> suggestedAnswers, String reason,
                                      List<ExperienceTheme> themes) {
}
