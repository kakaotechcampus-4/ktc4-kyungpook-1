package com.gitory.backend.common.infra.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.rate-limit")
public record RateLimitProperties(boolean enabled, Limit interviewAsk, Limit interviewAnswer, Limit cardDraft,
                                  Limit coverLetter, Limit coverLetterPolish, Limit coverLetterTurn,
                                  Limit coverLetterSuggest, Limit coverLetterRewrite,
                                  Limit matchJudgement, Limit matchOverall, Limit matchGapFill, Limit assistant, Limit feedback) {

    public RateLimitProperties {
        interviewAsk = interviewAsk == null ? Limit.NONE : interviewAsk;
        interviewAnswer = interviewAnswer == null ? Limit.NONE : interviewAnswer;
        cardDraft = cardDraft == null ? Limit.NONE : cardDraft;
        coverLetter = coverLetter == null ? Limit.NONE : coverLetter;
        coverLetterPolish = coverLetterPolish == null ? Limit.NONE : coverLetterPolish;
        coverLetterTurn = coverLetterTurn == null ? Limit.NONE : coverLetterTurn;
        coverLetterSuggest = coverLetterSuggest == null ? Limit.NONE : coverLetterSuggest;
        coverLetterRewrite = coverLetterRewrite == null ? Limit.NONE : coverLetterRewrite;
        matchJudgement = matchJudgement == null ? Limit.NONE : matchJudgement;
        matchOverall = matchOverall == null ? Limit.NONE : matchOverall;
        matchGapFill = matchGapFill == null ? Limit.NONE : matchGapFill;
        assistant = assistant == null ? Limit.NONE : assistant;
        feedback = feedback == null ? Limit.NONE : feedback;
    }

    public Limit of(RateLimitedAction action) {

        return switch (action) {
            case INTERVIEW_ASK -> interviewAsk;
            case INTERVIEW_ANSWER -> interviewAnswer;
            case CARD_DRAFT -> cardDraft;
            case COVER_LETTER -> coverLetter;
            case COVER_LETTER_POLISH -> coverLetterPolish;
            case COVER_LETTER_TURN -> coverLetterTurn;
            case COVER_LETTER_SUGGEST -> coverLetterSuggest;
            case COVER_LETTER_REWRITE -> coverLetterRewrite;
            case MATCH_JUDGEMENT -> matchJudgement;
            case MATCH_OVERALL -> matchOverall;
            case MATCH_GAP_FILL -> matchGapFill;
            case ASSISTANT -> assistant;
            case FEEDBACK -> feedback;
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
