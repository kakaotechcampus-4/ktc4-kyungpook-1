package com.gitory.backend.feedback.domain;

public interface FeedbackAlertSender {

    /** 알림 때문에 신고 접수가 실패하면 안 되므로, 구현은 보내지 못해도 예외를 밖으로 던지지 않는다 */
    void send(String text);
}
