package com.gitory.backend.support;

import com.gitory.backend.common.infra.ai.AiClientProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 테스트에 쓸 AI 설정을 만들고, 정하지 않은 칸은 AiClientProperties 의 기본값을 쓴다
 * AiClientProperties 에 칸이 늘어도 테스트는 그대로 두고 build 한 곳만 고친다
 */
public final class AiTestProperties {

    private URI baseUrl;
    private Duration connectTimeout;
    private Duration readTimeout;
    private Duration collectReadTimeout;
    private Duration groupsReadTimeout;
    private Duration interviewReadTimeout;
    private Duration diffEvidenceReadTimeout;
    private Duration starReadTimeout;

    private AiTestProperties() {
    }

    public static AiTestProperties defaults() {

        return new AiTestProperties();

    }

    public AiTestProperties baseUrl(URI baseUrl) {

        this.baseUrl = baseUrl;
        return this;

    }

    public AiTestProperties connectTimeout(Duration connectTimeout) {

        this.connectTimeout = connectTimeout;
        return this;

    }

    public AiTestProperties readTimeout(Duration readTimeout) {

        this.readTimeout = readTimeout;
        return this;

    }

    public AiTestProperties collectReadTimeout(Duration collectReadTimeout) {

        this.collectReadTimeout = collectReadTimeout;
        return this;

    }

    public AiTestProperties groupsReadTimeout(Duration groupsReadTimeout) {

        this.groupsReadTimeout = groupsReadTimeout;
        return this;

    }

    public AiTestProperties interviewReadTimeout(Duration interviewReadTimeout) {

        this.interviewReadTimeout = interviewReadTimeout;
        return this;

    }

    public AiTestProperties diffEvidenceReadTimeout(Duration diffEvidenceReadTimeout) {

        this.diffEvidenceReadTimeout = diffEvidenceReadTimeout;
        return this;

    }

    public AiTestProperties starReadTimeout(Duration starReadTimeout) {

        this.starReadTimeout = starReadTimeout;
        return this;

    }

    public AiClientProperties build() {

        return new AiClientProperties(baseUrl, connectTimeout, readTimeout, collectReadTimeout, groupsReadTimeout,
                interviewReadTimeout, diffEvidenceReadTimeout, starReadTimeout);

    }
}
