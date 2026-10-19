package com.gitory.backend.card.domain.interview;

import com.gitory.backend.recommend.domain.CandidateType;

public enum InterviewSourceType {
    PR,
    COMMIT_CLUSTER,
    DIRECT_CARD;

    public static InterviewSourceType of(CandidateType candidateType) {

        // 직접 추가한 후보는 커밋이 없어 AI 계약상 후보 없는 직접 작성 카드로 보낸다
        if (candidateType == null || candidateType == CandidateType.MANUAL) {
            return DIRECT_CARD;
        }

        return candidateType == CandidateType.PR ? PR : COMMIT_CLUSTER;

    }
}
