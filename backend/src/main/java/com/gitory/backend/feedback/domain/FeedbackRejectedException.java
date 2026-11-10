package com.gitory.backend.feedback.domain;

public class FeedbackRejectedException extends RuntimeException {

    public FeedbackRejectedException(String reason) {
        super(reason);
    }
}
