package com.gitory.backend.common.infra.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.rate-limit")
public record RateLimitProperties(boolean enabled, Limit interviewAsk, Limit interviewAnswer, Limit cardDraft,
                                  Limit coverLetter, Limit matchJudgement, Limit matchOverall) {

    public RateLimitProperties {
        interviewAsk = interviewAsk == null ? Limit.NONE : interviewAsk;
        interviewAnswer = interviewAnswer == null ? Limit.NONE : interviewAnswer;
        cardDraft = cardDraft == null ? Limit.NONE : cardDraft;
        coverLetter = coverLetter == null ? Limit.NONE : coverLetter;
        matchJudgement = matchJudgement == null ? Limit.NONE : matchJudgement;
        matchOverall = matchOverall == null ? Limit.NONE : matchOverall;
    }

    public Limit of(RateLimitedAction action) {

        return switch (action) {
            case INTERVIEW_ASK -> interviewAsk;
            case INTERVIEW_ANSWER -> interviewAnswer;
            case CARD_DRAFT -> cardDraft;
            case COVER_LETTER -> coverLetter;
            case MATCH_JUDGEMENT -> matchJudgement;
            case MATCH_OVERALL -> matchOverall;
        };

    }

    public record Limit(Integer perMinute, Integer perDay) {

        static final Limit NONE = new Limit(null, null);

        public Limit {
            if ((perMinute != null && perMinute < 1) || (perDay != null && perDay < 1)) {
                throw new IllegalArgumentException("gitory.rate-limit 의 한도는 1 이상이어야 한다");
            }
        }
    }
}
