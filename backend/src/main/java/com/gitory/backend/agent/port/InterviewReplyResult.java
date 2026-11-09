package com.gitory.backend.agent.port;

import java.util.List;

public record InterviewReplyResult(InterviewReplyVerdict verdict, String sentence, List<InterviewReplyExtra> extra,
                                   String followUp, String reason) {
}
