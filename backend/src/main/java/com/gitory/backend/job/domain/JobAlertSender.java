package com.gitory.backend.job.domain;

public interface JobAlertSender {

    /** 알림 때문에 Job 처리가 멈추면 안 되므로, 구현은 보내지 못해도 예외를 밖으로 던지지 않는다 */
    void send(String text);
}
