package com.gitory.backend.feedback.infra;

import com.gitory.backend.feedback.domain.FeedbackAlertSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Discord 웹훅으로 글 한 줄을 보내며, 주소가 비어 있으면 아무것도 보내지 않는다 */
@Slf4j
@Component
public class DiscordFeedbackAlertSender implements FeedbackAlertSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    // Discord 가 HTTP 라이브러리의 기본 User-Agent 를 막는 경우가 있어 직접 정한다
    private static final String USER_AGENT = "Gitory";

    private final String webhookUrl;
    private final RestClient client;
    private final ObjectMapper mapper;

    // Job 알림과 같은 gitory.alert.webhook-url 을 읽되, 같은 접두사의 속성 클래스를 하나 더 만들지 않으려고 값만 받는다
    public DiscordFeedbackAlertSender(@Value("${gitory.alert.webhook-url:}") String webhookUrl, ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(READ_TIMEOUT);
        this.webhookUrl = webhookUrl.strip();
        this.mapper = mapper;
        this.client = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(factory)
                .build();
    }

    /**
     * 보내다 난 예외는 밖으로 내지 않고 경고만 남긴다
     * 예외 메시지에 웹훅 주소(비밀 토큰이 들어 있다)가 담겨 있어 로그에는 예외 종류만 남긴다
     */
    @Override
    public void send(String text) {

        if (webhookUrl.isEmpty()) {
            return;
        }

        // 본문 길이를 모르면 chunked 로 나가므로 JSON 을 바이트로 만들어 Content-Length 가 붙게 한다
        try {
            client.post()
                    .uri(URI.create(webhookUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsBytes(Map.of("content", text)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException failure) {
            log.warn("문제 신고 알림을 보내지 못했다: {}", failure.getClass().getSimpleName());
        }

    }
}
