package com.gitory.backend.common.infra.ratelimit;

public enum RateLimitedAction {

    INTERVIEW_ASK("interview-ask"),
    INTERVIEW_ANSWER("interview-answer"),
    CARD_DRAFT("card-draft"),
    COVER_LETTER("cover-letter");

    private final String key;

    RateLimitedAction(String key) {
        this.key = key;
    }

    public String key() {

        return key;

    }
}
