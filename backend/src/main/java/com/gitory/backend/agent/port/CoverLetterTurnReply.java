package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterTurnReply(Message message, List<Answer> answers, String status) {

    public record Message(String text, int questionId, String item) {
    }

    public record Answer(String item, String text, boolean sufficient) {
    }
}
