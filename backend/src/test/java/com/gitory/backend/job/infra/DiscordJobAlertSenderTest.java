package com.gitory.backend.job.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DiscordJobAlertSenderTest {

    private static final String TEXT = "[Gitory] 분석 Job 7 이 INTERNAL_ERROR 로 실패했습니다";
    private static final String SECRET = "webhook-secret-token";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private volatile int responseStatus = 204;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestContentType;
    private volatile String requestContentLength;
    private volatile String requestTransferEncoding;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestMethod = exchange.getRequestMethod();
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestContentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            requestTransferEncoding = exchange.getRequestHeaders().getFirst("Transfer-Encoding");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    // 주소가 비면 가짜 서버로 갈 길이 없어 요청 수로는 못 가리므로, 보내려다 실패한 경고가 없는지로 시도하지 않았음을 본다
    @Test
    @DisplayName("알림 주소가 없으면 보내지 않는다")
    void sendsNothingWithoutWebhookUrl() {

        List<ILoggingEvent> logs = logsWhile(
                () -> new DiscordJobAlertSender(new JobAlertProperties(""), mapper).send(TEXT));

        assertThat(logs).isEmpty();

    }

    @Test
    @DisplayName("알림을 보내다 실패해도 예외를 밖으로 내지 않는다")
    void doesNotThrowWhenSendingFails() {

        responseStatus = 500;

        assertThatCode(() -> sender().send(TEXT)).doesNotThrowAnyException();
        assertThat(calls).hasValue(1);

    }

    @Test
    @DisplayName("알림은 content 한 칸짜리 JSON 으로 보낸다")
    void postsJsonWithOnlyContent() {

        sender().send(TEXT);

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestContentType).startsWith("application/json");
        assertThat(requestContentLength).isEqualTo(String.valueOf(requestBody.getBytes(StandardCharsets.UTF_8).length));
        assertThat(requestTransferEncoding).isNull();
        JsonNode body = mapper.readTree(requestBody);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.path("content").asString()).isEqualTo(TEXT);

    }

    @Test
    @DisplayName("연결에 실패해 예외 메시지에 주소가 담겨도 로그에는 웹훅 주소를 남기지 않는다")
    void doesNotLogWebhookUrl() throws IOException {

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        DiscordJobAlertSender sender = new DiscordJobAlertSender(
                new JobAlertProperties("http://127.0.0.1:" + closedPort + "/api/webhooks/123/" + SECRET), mapper);

        List<ILoggingEvent> logs = logsWhile(() -> sender.send(TEXT));

        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("ResourceAccessException").doesNotContain(SECRET);
            assertThat(event.getThrowableProxy()).isNull();
        });

    }

    @Test
    @DisplayName("응답이 오래 걸리면 기다리지 않고 포기한다")
    void givesUpWhenTheServerIsSlow() {

        responseDelayMs = 1000;
        DiscordJobAlertSender impatient = new DiscordJobAlertSender(new JobAlertProperties(webhookUrl()), mapper,
                Duration.ofSeconds(2), Duration.ofMillis(100));
        long startedAt = System.nanoTime();

        impatient.send(TEXT);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(800));

    }

    private DiscordJobAlertSender sender() {

        return new DiscordJobAlertSender(new JobAlertProperties(webhookUrl()), mapper);

    }

    private String webhookUrl() {

        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/123/" + SECRET;

    }

    private static List<ILoggingEvent> logsWhile(Runnable action) {

        Logger senderLog = (Logger) LoggerFactory.getLogger(DiscordJobAlertSender.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        senderLog.addAppender(logs);
        try {
            action.run();
        } finally {
            senderLog.detachAppender(logs);
        }

        return logs.list;

    }
}
