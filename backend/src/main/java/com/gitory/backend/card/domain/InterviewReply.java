package com.gitory.backend.card.domain;

import com.gitory.backend.card.domain.interview.InterviewTurnView;

import java.util.List;

public record InterviewReply(InterviewReplyOutcome outcome, String reason, List<WrittenSlot> written,
                             Short restoreVersionNo, CardDetail card, InterviewTurnView nextTurn) {
}
