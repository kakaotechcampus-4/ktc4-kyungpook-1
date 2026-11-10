package com.gitory.backend.common.infra.ratelimit;

public enum RateLimitedAction {

    INTERVIEW_ASK("interview-ask"),
    INTERVIEW_ANSWER("interview-answer"),
    CARD_DRAFT("card-draft"),
    COVER_LETTER("cover-letter"),
    COVER_LETTER_POLISH("cover-letter-polish"),
    COVER_LETTER_TURN("cover-letter-turn"),
    MATCH_JUDGEMENT("match-judgement"),
    MATCH_OVERALL("match-overall"),
    MATCH_GAP_FILL("match-gap-fill"),
    ASSISTANT("assistant"),
    FEEDBACK("feedback");

    private final String key;

    RateLimitedAction(String key) {
        this.key = key;
    }

    public String key() {

        return key;

    }
}
