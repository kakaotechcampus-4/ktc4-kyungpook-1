package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.EvidenceType;

public enum AnswerSource {
    TYPED,
    SELECTED;

    public EvidenceType evidenceType() {

        return switch (this) {
            case TYPED -> EvidenceType.USER_STATED;
            case SELECTED -> EvidenceType.USER_SELECTED;
        };

    }
}
