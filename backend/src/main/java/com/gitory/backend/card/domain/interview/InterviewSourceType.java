package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.Card;

public enum InterviewSourceType {
    PR,
    COMMIT_CLUSTER,
    DIRECT_CARD;

    public static InterviewSourceType of(Card card) {

        return DIRECT_CARD;

    }
}
