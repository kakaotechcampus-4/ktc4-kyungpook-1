package com.gitory.backend.mail.domain;

public interface MailSender {

    void send(String to, String subject, String html);
}
