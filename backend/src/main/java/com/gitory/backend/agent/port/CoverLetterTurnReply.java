package com.gitory.backend.agent.port;

public record CoverLetterTurnReply(Message message, Answer answer, String status) {

    public record Message(String text, int questionId, String item) {
    }

    public record Answer(String item, String text, boolean sufficient) {
    }
}
