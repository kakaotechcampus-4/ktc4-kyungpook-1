package com.gitory.backend.common.infra.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 내부 AI HTTP 경계. 원문 에러와 HTTP 예외의 cause를 밖으로 전달하지 않는다. */
@Slf4j
@Component
@EnableConfigurationProperties(AiClientProperties.class)
public class AiHttpClient {

    private static final Set<String> ERROR_CODES = Set.of("INVALID_PAYLOAD", "UNAUTHORIZED",
            "RESOURCE_NOT_FOUND", "NO_TOOL_CALL", "EVIDENCE_MISMATCH", "LLM_RATE_LIMIT",
            "GITHUB_API_ERROR", "GITHUB_UNAUTHORIZED", "LLM_UNAVAILABLE", "INTERNAL_ERROR");

    private final AiClientProperties properties;
    private final RestClient collectionClient;
    private final RestClient queryClient;
    // JDK HttpClient 는 만들 때마다 스레드를 하나 띄우므로 대기 시간마다 한 번만 만들어 다시 쓴다
    private final Map<Duration, RestClient> postClients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final AiCallRecorder recorder;

    public AiHttpClient(AiClientProperties properties, ObjectMapper mapper) {
        this(properties, mapper, AiCallRecorder.NONE);
    }

    @Autowired
    public AiHttpClient(AiClientProperties properties, ObjectMapper mapper, AiCallRecorder recorder) {
        this.properties = properties;
        this.mapper = mapper;
        this.recorder = recorder;
        this.collectionClient = client(properties, properties.collectReadTimeout());
        // health·OpenAPI 조회가 긴 수집 요청 제한 때문에 정체되지 않게 한다.
        this.queryClient = client(properties, Duration.ofSeconds(5));
    }

    /** health·OpenAPI 조회는 봉투가 없는 원본 JSON을 반환한다. */
    public JsonNode get(String path) {
        requireInternalPath(path);
        return exchange(queryClient.get().uri(path), false, new Seen());
    }

    /** consent 모듈만 토큰을 복호화하고 이 메서드의 헤더에 전달한다. */
    public JsonNode collect(Object request, String githubToken) {
        requireHeaderSafe(githubToken);
        return recorded("/internal/collect", collectionClient.post().uri("/internal/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Token", githubToken)
                .body(request), null);
    }

    /** {@link #collect} 와 같은 수집을 부르고, AI 가 PR·커밋을 하나 읽을 때마다 보내는 진행을 progress 에 넘긴다 */
    public JsonNode collectStreaming(Object request, String githubToken, Consumer<AiProgress> progress) {

        requireHeaderSafe(githubToken);
        return recorded("/internal/collect/stream", collectionClient.post().uri("/internal/collect/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Token", githubToken)
                .body(request), progress);

    }

    /** {@link #post} 와 같은 호출을 진행 스트리밍 경로로 부르고, 받은 진행을 progress 에 넘긴다 */
    public JsonNode postStreaming(String path, Object request, Duration readTimeout, Consumer<AiProgress> progress) {

        requireInternalPath(path);
        RestClient postClient = postClients.computeIfAbsent(readTimeout, timeout -> client(properties, timeout));
        return recorded(path, postClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request), progress);

    }

    /** 수집 말고도 토큰이 필요한 AI 호출로, 호출마다 기다릴 시간이 달라 경로와 대기 시간을 함께 받는다 */
    public JsonNode postWithGithubToken(String path, Object request, String githubToken, Duration readTimeout) {

        requireInternalPath(path);
        requireHeaderSafe(githubToken);
        RestClient postClient = postClients.computeIfAbsent(readTimeout, timeout -> client(properties, timeout));
        return recorded(path, postClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Token", githubToken)
                .body(request), null);

    }

    /** 토큰이 필요 없는 AI 호출로, 경험 그룹화·되묻기처럼 호출마다 기다릴 시간이 달라 대기 시간을 함께 받는다 */
    public JsonNode post(String path, Object request, Duration readTimeout) {

        requireInternalPath(path);
        RestClient postClient = postClients.computeIfAbsent(readTimeout, timeout -> client(properties, timeout));
        return recorded(path, postClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request), null);

    }

    /**
     * 응답 형식이 있는 호출을 부르고, 성공·실패와 상관없이 AI 가 준 처리 기록과 함께 한 번 남긴다
     * progress 가 있으면 진행 스트리밍으로 부르고, 진행 줄이 아니라 마지막 결과 줄의 상태·처리 기록을 남긴다
     */
    private JsonNode recorded(String path, RestClient.RequestHeadersSpec<?> request, Consumer<AiProgress> progress) {

        long started = System.nanoTime();
        Seen seen = new Seen();
        try {
            JsonNode data = progress == null ? exchange(request, true, seen) : streamingExchange(request, progress, seen);
            record(path, seen, null, started);
            return data;
        } catch (AiClientException failed) {
            record(path, seen, failed.errorCode(), started);
            throw failed;
        }

    }

    // 기록을 남기지 못해도 AI 호출의 결과는 그대로 돌려준다
    private void record(String path, Seen seen, String errorCode, long started) {

        try {
            recorder.record(new AiCall(path, AiCallContext.jobId(), AiCallContext.cardId(), seen.status, errorCode,
                    seen.meta, Duration.ofNanos(System.nanoTime() - started).toMillis()));
        } catch (RuntimeException failed) {
            log.warn("AI 호출 기록을 남기지 못했다: {}", path, failed);
        }

    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, boolean envelope, Seen seen) {
        try {
            return request.exchange((ignored, response) -> {
                int status = response.getStatusCode().value();
                seen.status = status;
                JsonNode body;
                try {
                    body = mapper.readTree(response.getBody());
                } catch (JacksonIOException interruptedBody) {
                    // 응답 헤더 뒤에 끊긴 body도 네트워크/시간 제한 오류로 재시도할 수 있다.
                    throw new AiClientException("AI_UNAVAILABLE", true, status);
                } catch (RuntimeException malformed) {
                    if (status < 200 || status >= 300) {
                        throw httpFailure(status, null);
                    }
                    throw AiClientException.invalidResponse();
                }
                seen.meta = body == null ? null : body.get("meta");
                if (status < 200 || status >= 300) {
                    throw httpFailure(status, body);
                }
                if (body == null || !body.isObject()) {
                    throw AiClientException.invalidResponse();
                }
                return envelope ? unwrap(body, status) : body;
            });
        } catch (AiClientException safe) {
            throw safe;
        } catch (ResourceAccessException unreachable) {
            throw new AiClientException("AI_UNAVAILABLE", true, null);
        } catch (RestClientException invalid) {
            throw AiClientException.invalidResponse();
        }
    }

    /**
     * 한 줄에 JSON 하나인 응답에서 진행 줄은 progress 에 넘기고, 마지막 결과 줄은 스트리밍 없는 응답과 똑같이 검사해 돌려준다
     * 읽을 수 없는 줄은 진행 표시만 빠지도록 버리고, 결과 줄 없이 끝나면 끊긴 응답처럼 다시 시도할 수 있는 실패로 본다
     */
    private JsonNode streamingExchange(RestClient.RequestHeadersSpec<?> request, Consumer<AiProgress> progress,
                                       Seen seen) {
        try {
            return request.exchange((ignored, response) -> {
                int status = response.getStatusCode().value();
                seen.status = status;
                if (status < 200 || status >= 300) {
                    // 요청 검증처럼 스트림을 열기 전에 난 실패는 보통 JSON 응답으로 온다
                    JsonNode body = readOrNull(response.getBody().readAllBytes());
                    seen.meta = body == null ? null : body.get("meta");
                    throw httpFailure(status, body);
                }
                BufferedReader lines = new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        JsonNode item = readOrNull(line.getBytes(StandardCharsets.UTF_8));
                        if (item == null) {
                            continue;
                        }
                        String type = item.path("type").asString("");
                        if ("progress".equals(type)) {
                            progressOf(item).ifPresent(progress);
                        } else if ("result".equals(type)) {
                            return resultOf(item, seen);
                        }
                    }
                } catch (IOException | JacksonIOException interrupted) {
                    throw new AiClientException("AI_UNAVAILABLE", true, status);
                }
                throw new AiClientException("AI_UNAVAILABLE", true, status);
            });
        } catch (AiClientException safe) {
            throw safe;
        } catch (ResourceAccessException unreachable) {
            throw new AiClientException("AI_UNAVAILABLE", true, null);
        } catch (RestClientException invalid) {
            throw AiClientException.invalidResponse();
        }
    }

    private JsonNode readOrNull(byte[] json) {
        try {
            return json.length == 0 ? null : mapper.readTree(json);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Optional<AiProgress> progressOf(JsonNode item) {
        JsonNode step = item.path("step");
        JsonNode done = item.path("done");
        JsonNode total = item.path("total");
        if (!step.isString() || !done.isIntegralNumber()) {
            return Optional.empty();
        }
        return Optional.of(new AiProgress(step.asString(), done.asInt(), total.isIntegralNumber() ? total.asInt() : null));
    }

    // 스트림은 200 으로 열리므로 기록에는 AI 가 결과 줄에 실은 상태와 처리 기록을 남긴다
    private static JsonNode resultOf(JsonNode item, Seen seen) {
        int status = item.path("status").asInt(0);
        JsonNode body = item.get("body");
        seen.status = status;
        seen.meta = body == null ? null : body.get("meta");
        if (status < 200 || status >= 300) {
            throw httpFailure(status, body);
        }
        if (body == null || !body.isObject()) {
            throw AiClientException.invalidResponse();
        }
        return unwrap(body, status);
    }

    private static JsonNode unwrap(JsonNode body, int status) {
        if (!body.path("success").isBoolean()) {
            throw AiClientException.invalidResponse();
        }
        if (!body.path("success").booleanValue()) {
            throw envelopeFailure(body, status);
        }
        JsonNode data = body.path("data");
        if (!data.isObject() || !body.path("error").isNull()) {
            throw AiClientException.invalidResponse();
        }
        return data;
    }

    private static AiClientException httpFailure(int status, JsonNode body) {
        if (body != null && body.isObject() && body.path("success").isBoolean()
                && !body.path("success").booleanValue() && body.path("error").isObject()) {
            return envelopeFailure(body, status);
        }
        return new AiClientException("AI_HTTP_ERROR", status == 429 || status >= 500, status);
    }

    private static AiClientException envelopeFailure(JsonNode body, int status) {
        JsonNode error = body.path("error");
        if (!error.path("code").isString() || !error.path("retryable").isBoolean()) {
            return new AiClientException("AI_INVALID_RESPONSE", false, status);
        }
        String code = error.path("code").asString();
        if (!ERROR_CODES.contains(code)) {
            return new AiClientException("AI_INVALID_RESPONSE", false, status);
        }
        return new AiClientException(code, error.path("retryable").booleanValue(), status);
    }

    /**
     * HTTP 버전을 1.1 로 고정한다
     * 정하지 않으면 요청마다 HTTP/2 업그레이드(Upgrade: h2c)를 붙이는데, AI 의 uvicorn 이 이런 요청의 본문을 버려 모든 수집이 400 이 된다
     */
    private static RestClient client(AiClientProperties properties, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(properties.baseUrl().toString())
                .requestFactory(factory).build();
    }

    // JDK의 invalid header value 예외에는 원문이 포함된다. 헤더 생성 전에 거절한다.
    private static void requireHeaderSafe(String githubToken) {
        if (githubToken == null || githubToken.isBlank()
                || githubToken.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new AiClientException("GITHUB_CONNECTION_UNAVAILABLE", false, null);
        }
    }

    private static void requireInternalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains(":") || path.contains("?")) {
            throw new IllegalArgumentException("AI 경로는 서버 내부의 절대 경로여야 한다");
        }
    }

    private static final class Seen {

        private Integer status;
        private JsonNode meta;
    }
}
