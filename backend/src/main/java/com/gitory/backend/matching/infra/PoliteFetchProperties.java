package com.gitory.backend.matching.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("gitory.matching.fetch")
public record PoliteFetchProperties(Duration connectTimeout, Duration readTimeout, Long maxBodyBytes,
                                    Duration minInterval, String userAgent) {

    public PoliteFetchProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        maxBodyBytes = maxBodyBytes == null ? 20L * 1024 * 1024 : maxBodyBytes;
        minInterval = minInterval == null ? Duration.ofSeconds(1) : minInterval;
        userAgent = userAgent == null
                ? "Gitory/1.0 (+https://github.com/kakaotechcampus-4/ktc4-kyungpook-1)" : userAgent;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()
                || minInterval.isNegative() || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("공고 가져오기 설정은 시간 제한과 크기 상한이 양수, 간격이 0 이상이어야 한다");
        }
    }
}
