package com.gitory.backend.mail.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 메일을 별도 스레드로 보내 저장·알림 확인이 메일 서버를 기다리지 않게 하며, 실패해도 기록만 하고 다시 보내지 않는다
 * 실패 로그에는 받는 사람 주소를 적지 않는다 — 메일 라이브러리 예외 메시지에도 주소가 들어 있어 예외는 종류만 남긴다
 */
@Slf4j
@Service
@EnableConfigurationProperties(MailProperties.class)
public class MailService {

    private final MailSender sender;
    private final MailProperties properties;
    private final Executor sendCalls;

    @Autowired
    public MailService(MailSender sender, MailProperties properties) {
        this(sender, properties, Executors.newFixedThreadPool(2,
                Thread.ofPlatform().daemon().name("mail-send-", 1).factory()));
    }

    MailService(MailSender sender, MailProperties properties, Executor sendCalls) {
        this.sender = sender;
        this.properties = properties;
        this.sendCalls = sendCalls;
    }

    public void sendOptInConfirmation(String to) {

        send(to, () -> MailContent.optInConfirmation(properties.appBaseUrl()));

    }

    public void sendMatchedPostings(String to, List<MailPosting> postings) {

        send(to, () -> MailContent.matchedPostings(properties.appBaseUrl(), postings));

    }

    private void send(String to, Supplier<MailMessage> message) {

        if (!properties.enabled()) {
            return;
        }
        try {
            sendCalls.execute(() -> deliver(to, message));
        } catch (RuntimeException failure) {
            log.warn("메일을 보내지 못했다: {}", failure.getClass().getSimpleName());
        }

    }

    private void deliver(String to, Supplier<MailMessage> message) {

        try {
            MailMessage built = message.get();
            sender.send(to, built.subject(), built.html());
        } catch (RuntimeException failure) {
            log.warn("메일을 보내지 못했다: {}", failure.getClass().getSimpleName());
        }

    }
}
