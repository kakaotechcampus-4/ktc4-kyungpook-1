package com.gitory.backend.common.infra.ai;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** AI 프로세스까지의 실제 HTTP 연결을 검사한다. 모델·분석 준비 상태를 보증하지 않는다. */
@Component
public class AiHealthIndicator implements HealthIndicator {

    private final AiHttpClient client;

    public AiHealthIndicator(AiHttpClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            JsonNode response = client.get("/health");
            if (!"ok".equals(response.path("status").asString())
                    || !"gitory-ai".equals(response.path("service").asString())) {
                return Health.down().withDetail("code", "AI_INVALID_HEALTH_RESPONSE").build();
            }
            return Health.up().build();
        } catch (AiClientException failure) {
            // 공개 health 응답/로그에 원문 응답과 예외·네트워크 주소를 넣지 않는다.
            return Health.down().withDetail("code", failure.errorCode()).build();
        }
    }
}
