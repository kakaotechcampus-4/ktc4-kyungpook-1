package com.gitory.backend.mail.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MailServiceTest {

    private static final String TO = "secret.person@example.com";
    private static final MailPosting POSTING = new MailPosting(UUID.randomUUID(), "토스", "백엔드", 3, 4, Instant.now());

    private final MailSender sender = mock(MailSender.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor queueingExecutor = queued::add;
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(MailService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {

        logs.start();
        serviceLogger.addAppender(logs);

    }

    @AfterEach
    void releaseLogs() {

        serviceLogger.detachAppender(logs);

    }

    private MailService service(boolean enabled) {

        return new MailService(sender, new MailProperties(enabled, "https://gitory.example"), queueingExecutor);

    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 두 메일 모두 아무것도 보내지 않는다")
    void sendsNothingWhenTheSwitchIsOff() {

        MailService off = service(false);

        off.sendOptInConfirmation(TO);
        off.sendMatchedPostings(TO, List.of(POSTING));

        assertThat(queued).isEmpty();
        verify(sender, never()).send(any(), any(), any());

    }

    @Test
    @DisplayName("보내기는 부른 쪽에서 끝내지 않고 실행기에 넘기며 실행기가 돌릴 때 메일 서버로 보낸다")
    void handsTheSendingToTheExecutor() {

        service(true).sendOptInConfirmation(TO);

        verify(sender, never()).send(any(), any(), any());
        assertThat(queued).hasSize(1);

        queued.get(0).run();

        verify(sender).send(eq(TO), eq("[Gitory] 이제 이 주소로 맞는 공고를 알려 드려요"), any());

    }

    @Test
    @DisplayName("보내다 난 예외는 밖으로 나가지 않고 경고만 남기며 로그에 메일 주소를 적지 않는다")
    void swallowsFailuresAndNeverLogsTheAddress() {

        doThrow(new IllegalStateException("550 invalid recipient " + TO)).when(sender).send(any(), any(), any());
        service(true).sendMatchedPostings(TO, List.of(POSTING));

        assertThatCode(() -> queued.get(0).run()).doesNotThrowAnyException();

        assertThat(logs.list).hasSize(1);
        ILoggingEvent warning = logs.list.get(0);
        assertThat(warning.getLevel().toString()).isEqualTo("WARN");
        assertThat(warning.getFormattedMessage()).doesNotContain(TO);
        assertThat(warning.getThrowableProxy()).isNull();

    }
}
