package com.gitory.backend.matching.infra;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * 회사 채용 사이트에 예의를 지키며 요청하는 클라이언트로, 실행 한 번마다 세션을 열어 쓴다
 * RestClient 대신 JDK HttpClient 를 쓰는 것은 본문이 크기 상한을 넘는 순간 받기를 끊기 위해서다
 */
@Component
@EnableConfigurationProperties(PoliteFetchProperties.class)
public class PoliteFetcher {

    private final HttpClient client;
    private final PoliteFetchProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public PoliteFetcher(PoliteFetchProperties properties, Clock clock) {
        this(properties, clock, duration -> Thread.sleep(duration));
    }

    PoliteFetcher(PoliteFetchProperties properties, Clock clock, Sleeper sleeper) {
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public PoliteFetchSession open() {

        return new PoliteFetchSession(client, properties, clock, sleeper);

    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }
}
