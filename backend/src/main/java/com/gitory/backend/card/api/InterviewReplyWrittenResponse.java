package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.WrittenSlot;

public record InterviewReplyWrittenResponse(StarField field, String text) {

    static InterviewReplyWrittenResponse from(WrittenSlot written) {

        return new InterviewReplyWrittenResponse(written.field(), written.text());

    }
}
