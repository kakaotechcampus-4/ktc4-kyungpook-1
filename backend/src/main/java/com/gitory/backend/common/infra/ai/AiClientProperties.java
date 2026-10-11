package com.gitory.backend.common.infra.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** AI 주소와 시간 제한. 인증 토큰은 설정에 보관하지 않는다. */
@ConfigurationProperties("gitory.ai")
public record AiClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout,
                                 Duration collectReadTimeout) {

    public AiClientProperties {
        baseUrl = baseUrl == null ? URI.create("http://localhost:8000") : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(180) : readTimeout;
        collectReadTimeout = collectReadTimeout == null ? Duration.ofSeconds(600) : collectReadTimeout;
        if ((!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("gitory.ai.base-url 은 인증 정보 없는 HTTP(S) 주소여야 한다");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()
                || collectReadTimeout.isNegative() || collectReadTimeout.isZero()) {
            throw new IllegalArgumentException("AI 시간 제한은 양수여야 한다");
        }
    }
}
