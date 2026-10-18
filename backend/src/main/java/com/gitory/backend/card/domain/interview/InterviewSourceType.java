package com.gitory.backend.card.domain.interview;

import com.gitory.backend.recommend.domain.CandidateType;

public enum InterviewSourceType {
    PR,
    COMMIT_CLUSTER,
    DIRECT_CARD;

    public static InterviewSourceType of(CandidateType candidateType) {

        if (candidateType == null) {
            return DIRECT_CARD;
        }

        return candidateType == CandidateType.PR ? PR : COMMIT_CLUSTER;

    }
}
