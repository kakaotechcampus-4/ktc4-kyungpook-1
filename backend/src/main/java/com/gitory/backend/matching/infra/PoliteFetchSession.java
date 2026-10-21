package com.gitory.backend.matching.infra;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 실행 한 번 동안 robots.txt 와 호스트별 마지막 요청 시각을 기억하며, 한 스레드에서만 쓴다 */
@Slf4j
public final class PoliteFetchSession {

    private final HttpClient client;
    private final PoliteFetchProperties properties;
    private final Clock clock;
    private final PoliteFetcher.Sleeper sleeper;
    private final Map<String, Host> hosts = new HashMap<>();

    PoliteFetchSession(HttpClient client, PoliteFetchProperties properties, Clock clock,
                       PoliteFetcher.Sleeper sleeper) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public String get(URI url) {

        requireWebUrl(url);
        Host host = hosts.computeIfAbsent(originOf(url), origin -> new Host());
        if (host.robots == null && !host.robotsUnavailable) {
            loadRobots(url, host);
        }
        if (host.robotsUnavailable) {
            throw new PoliteFetchException(FetchFailureCode.ROBOTS_UNAVAILABLE,
                    "robots.txt 를 읽지 못해 요청하지 않는다: " + hostLabel(url));
        }
        if (host.robots.crawlDelay().compareTo(properties.maxCrawlDelay()) > 0) {
            throw new PoliteFetchException(FetchFailureCode.CRAWL_DELAY_TOO_LONG,
                    "robots.txt 의 Crawl-delay " + host.robots.crawlDelay().toSeconds() + "초가 상한 "
                            + properties.maxCrawlDelay().toSeconds() + "초를 넘어 요청하지 않는다: " + hostLabel(url));
        }
        String target = targetOf(url);
        if (!host.robots.isAllowed(target)) {
            log.info("robots.txt 가 막아 건너뜀: {}{}", hostLabel(url), target);
            throw new PoliteFetchException(FetchFailureCode.ROBOTS_DISALLOWED,
                    "robots.txt 가 막은 경로: " + hostLabel(url) + target);
        }
        Reply reply = send(url, host);
        if (reply.status == 429) {
            throw new PoliteFetchException(FetchFailureCode.HTTP_429, "요청이 너무 많다는 응답: " + hostLabel(url));
        }
        if (reply.status != 200) {
            throw new PoliteFetchException(FetchFailureCode.HTTP_STATUS,
                    "응답 상태 " + reply.status + ": " + hostLabel(url) + target);
        }
        return reply.body;

    }

    private void loadRobots(URI url, Host host) {

        URI robotsUrl = URI.create(url.getScheme() + "://" + hostLabel(url) + "/robots.txt");
        try {
            Reply reply = send(robotsUrl, host);
            if (reply.status == 200) {
                host.robots = RobotsTxt.parse(reply.body);
            } else if (reply.status >= 400 && reply.status < 500) {
                host.robots = RobotsTxt.allowAll();
            } else {
                host.robotsUnavailable = true;
            }
        } catch (PoliteFetchException e) {
            if (e.code() == FetchFailureCode.INTERRUPTED) {
                throw e;
            }
            host.robotsUnavailable = true;
        }

    }

    private Reply send(URI url, Host host) {

        waitForTurn(host);
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(properties.readTimeout())
                .header("User-Agent", properties.userAgent())
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(request, info -> new CappedBody(properties.maxBodyBytes()));
        try {
            HttpResponse<byte[]> response = pending.get(properties.readTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new PoliteFetchException(FetchFailureCode.INTERRUPTED, "요청 중 중단됐다: " + hostLabel(url));
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new PoliteFetchException(FetchFailureCode.TIMEOUT, "응답을 끝까지 받지 못했다: " + hostLabel(url));
        } catch (ExecutionException e) {
            throw new PoliteFetchException(failureOf(e), "요청에 실패했다: " + hostLabel(url));
        } finally {
            host.lastFinishedMillis = clock.millis();
        }

    }

    private void waitForTurn(Host host) {

        if (host.lastFinishedMillis < 0) {
            return;
        }
        Duration interval = properties.minInterval();
        if (host.robots != null) {
            Duration delay = host.robots.crawlDelay();
            interval = delay.compareTo(interval) > 0 ? delay : interval;
        }
        Duration remaining = interval.minusMillis(clock.millis() - host.lastFinishedMillis);
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        try {
            sleeper.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoliteFetchException(FetchFailureCode.INTERRUPTED, "기다리다 중단됐다");
        }

    }

    private static FetchFailureCode failureOf(Throwable error) {

        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return FetchFailureCode.TOO_LARGE;
            }
            if (cause instanceof HttpTimeoutException) {
                return FetchFailureCode.TIMEOUT;
            }
        }
        return FetchFailureCode.CONNECTION_FAILED;

    }

    private static void requireWebUrl(URI url) {

        String scheme = url.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || url.getHost() == null) {
            throw new IllegalArgumentException("http 또는 https 주소만 가져온다: " + url);
        }

    }

    private static String originOf(URI url) {

        return url.getScheme().toLowerCase(Locale.ROOT) + "://" + hostLabel(url);

    }

    private static String hostLabel(URI url) {

        String host = url.getHost().toLowerCase(Locale.ROOT);
        return url.getPort() < 0 ? host : host + ":" + url.getPort();

    }

    private static String targetOf(URI url) {

        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        return url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();

    }

    private static final class Host {

        RobotsTxt robots;
        boolean robotsUnavailable;
        long lastFinishedMillis = -1;
    }

    private record Reply(int status, String body) {
    }

    private static final class BodyTooLargeException extends IOException {
    }

    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long maxBytes;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {

            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);

        }

        @Override
        public void onNext(List<ByteBuffer> items) {

            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (collected.size() + (long) item.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                collected.writeBytes(chunk);
            }

        }

        @Override
        public void onError(Throwable error) {

            result.completeExceptionally(error);

        }

        @Override
        public void onComplete() {

            result.complete(collected.toByteArray());

        }

        @Override
        public CompletableFuture<byte[]> getBody() {

            return result;

        }
    }
}
