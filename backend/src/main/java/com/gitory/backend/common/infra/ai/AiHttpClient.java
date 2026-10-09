package com.gitory.backend.common.infra.ai;

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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

/** 내부 AI HTTP 경계. 원문 에러와 HTTP 예외의 cause를 밖으로 전달하지 않는다. */
@Component
@EnableConfigurationProperties(AiClientProperties.class)
public class AiHttpClient {

    private static final Set<String> ERROR_CODES = Set.of("INVALID_PAYLOAD", "UNAUTHORIZED",
            "RESOURCE_NOT_FOUND", "NO_TOOL_CALL", "EVIDENCE_MISMATCH", "LLM_RATE_LIMIT",
            "GITHUB_API_ERROR", "LLM_UNAVAILABLE", "INTERNAL_ERROR");

    private final RestClient collectionClient;
    private final RestClient queryClient;
    private final ObjectMapper mapper;

    public AiHttpClient(AiClientProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.collectionClient = client(properties, properties.collectReadTimeout());
        // health·OpenAPI 조회가 긴 수집 요청 제한 때문에 정체되지 않게 한다.
        this.queryClient = client(properties, Duration.ofSeconds(5));
    }

    /** health·OpenAPI 조회는 봉투가 없는 원본 JSON을 반환한다. */
    public JsonNode get(String path) {
        requireInternalPath(path);
        return exchange(queryClient.get().uri(path), false);
    }

    /** consent 모듈만 토큰을 복호화하고 이 메서드의 헤더에 전달한다. */
    public JsonNode collect(Object request, String githubToken) {
        if (githubToken == null || githubToken.isBlank()
                || githubToken.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            // JDK의 invalid header value 예외에는 원문이 포함된다. 헤더 생성 전에 거절한다.
            throw new AiClientException("GITHUB_CONNECTION_UNAVAILABLE", false, null);
        }
        return exchange(collectionClient.post().uri("/internal/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Token", githubToken)
                .body(request), true);
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, boolean envelope) {
        try {
            return request.exchange((ignored, response) -> {
                int status = response.getStatusCode().value();
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

    private static void requireInternalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains(":") || path.contains("?")) {
            throw new IllegalArgumentException("AI 경로는 서버 내부의 절대 경로여야 한다");
        }
    }
}
