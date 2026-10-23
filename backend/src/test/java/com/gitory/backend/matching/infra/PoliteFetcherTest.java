package com.gitory.backend.matching.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(30)
@ExtendWith(OutputCaptureExtension.class)
class PoliteFetcherTest {

    private static final String ROBOTS = "/robots.txt";

    private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final FakeClock clock = new FakeClock();

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            userAgents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            HttpHandler route = routes.get(exchange.getRequestURI().getPath());
            if (route == null) {
                write(exchange, 404, "");
            } else {
                route.handle(exchange);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("같은 호스트에는 요청 사이를 1초 띄운다")
    void spacesRequestsToSameHostByOneSecond() {

        serve("/a", 200, "a");
        serve("/b", 200, "b");
        PoliteFetchSession session = session(null, null);

        session.get(URI.create(base + "/a"));
        session.get(URI.create(base + "/b"));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));

    }

    @Test
    @DisplayName("Crawl-delay 가 1초보다 크면 그 값만큼 띄운다")
    void crawlDelayLongerThanDefaultIsUsed() {

        serve(ROBOTS, 200, "User-agent: *\nCrawl-delay: 3\n");
        serve("/a", 200, "a");

        session(null, null).get(URI.create(base + "/a"));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));

    }

    @Test
    @DisplayName("Crawl-delay 가 상한 60초와 같으면 그만큼 기다리고 61초면 요청하지 않고 실패한다")
    void crawlDelayAboveLimitSkipsHost() {

        serve(ROBOTS, 200, "User-agent: *\nCrawl-delay: 60\n");
        serve("/a", 200, "a");
        session(null, null).get(URI.create(base + "/a"));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
        hits.clear();
        serve(ROBOTS, 200, "User-agent: *\nCrawl-delay: 61\n");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/a")));

        assertThat(code).isEqualTo(FetchFailureCode.CRAWL_DELAY_TOO_LONG);
        assertThat(hits).containsExactly(ROBOTS);

    }

    @Test
    @DisplayName("robots.txt 는 호스트마다 실행 한 번에 한 번만 읽는다")
    void readsRobotsOncePerHost() {

        serve("/a", 200, "a");
        serve("/b", 200, "b");
        PoliteFetchSession session = session(null, null);

        session.get(URI.create(base + "/a"));
        session.get(URI.create(base + "/b"));

        assertThat(hits).containsExactly(ROBOTS, "/a", "/b");

    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    @DisplayName("robots.txt 가 4xx 면 제한 없이 요청한다")
    void robotsClientErrorMeansNoRestriction(int status) {

        serve(ROBOTS, status, "");
        serve("/jobs", 200, "ok");

        String body = session(null, null).get(URI.create(base + "/jobs"));

        assertThat(body).isEqualTo("ok");

    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 302})
    @DisplayName("robots.txt 가 5xx 거나 넘겨 주면 그 호스트는 이번 실행에서 실패하고 다시 묻지 않는다")
    void robotsServerErrorFailsHostForTheRun(int status) {

        serve(ROBOTS, status, "");
        serve("/jobs", 200, "ok");
        PoliteFetchSession session = session(null, null);

        FetchFailureCode first = failureOf(() -> session.get(URI.create(base + "/jobs")));
        FetchFailureCode second = failureOf(() -> session.get(URI.create(base + "/jobs")));

        assertThat(first).isEqualTo(FetchFailureCode.ROBOTS_UNAVAILABLE);
        assertThat(second).isEqualTo(FetchFailureCode.ROBOTS_UNAVAILABLE);
        assertThat(hits).containsExactly(ROBOTS);

    }

    @Test
    @DisplayName("robots.txt 가 넘겨 준 주소를 따라가 끝에서 받은 글을 robots.txt 로 읽는다")
    void followsRobotsRedirectAndReadsFinalBody() {

        redirect(ROBOTS, "/moved-robots.txt");
        serve("/moved-robots.txt", 200, "User-agent: *\nDisallow: /private");
        serve("/jobs", 200, "ok");
        PoliteFetchSession session = session(null, null);

        String body = session.get(URI.create(base + "/jobs"));
        FetchFailureCode blocked = failureOf(() -> session.get(URI.create(base + "/private/1")));

        assertThat(body).isEqualTo("ok");
        assertThat(blocked).isEqualTo(FetchFailureCode.ROBOTS_DISALLOWED);
        assertThat(hits).containsExactly(ROBOTS, "/moved-robots.txt", "/jobs");

    }

    @Test
    @DisplayName("robots.txt 가 넘겨 준 끝이 규칙 없는 오류 페이지나 4xx 면 제한 없이 요청한다")
    void robotsRedirectToErrorPageMeansNoRestriction() {

        redirect(ROBOTS, "/error/404");
        redirect("/error/404", "/error/404.html");
        serve("/error/404.html", 200, "<html><body>페이지를 찾을 수 없어요</body></html>");
        serve("/jobs", 200, "ok");

        assertThat(session(null, null).get(URI.create(base + "/jobs"))).isEqualTo("ok");

    }

    @Test
    @DisplayName("robots.txt 가 다섯 번 넘게 넘겨 주면 읽지 못한 것으로 보고 그 호스트에 요청하지 않는다")
    void tooManyRobotsRedirectsFailHost() {

        for (int hop = 0; hop < 6; hop++) {
            redirect(hop == 0 ? ROBOTS : "/r" + hop, "/r" + (hop + 1));
        }
        serve("/r6", 200, "");
        serve("/jobs", 200, "ok");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/jobs")));

        assertThat(code).isEqualTo(FetchFailureCode.ROBOTS_UNAVAILABLE);
        assertThat(hits).containsExactly(ROBOTS, "/r1", "/r2", "/r3", "/r4", "/r5");

    }

    @Test
    @DisplayName("robots.txt 에 연결하지 못해도 그 호스트는 실패한다")
    void robotsConnectionFailureFailsHost() throws IOException {

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        FetchFailureCode code = failureOf(() ->
                session(null, null).get(URI.create("http://127.0.0.1:" + closedPort + "/jobs")));

        assertThat(code).isEqualTo(FetchFailureCode.ROBOTS_UNAVAILABLE);

    }

    @Test
    @DisplayName("robots.txt 응답이 멈춰도 그 호스트는 이번 실행에서 실패한다")
    void robotsTimeoutFailsHost() {

        routes.put(ROBOTS, exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write("User-agent: *".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepQuietly(3000);
        });
        serve("/jobs", 200, "ok");

        FetchFailureCode code = failureOf(() ->
                session(Duration.ofMillis(500), null).get(URI.create(base + "/jobs")));

        assertThat(code).isEqualTo(FetchFailureCode.ROBOTS_UNAVAILABLE);
        assertThat(hits).doesNotContain("/jobs");

    }

    @Test
    @DisplayName("robots.txt 와 요청 간격은 호스트마다 따로 기억한다")
    void remembersRobotsAndIntervalPerHost() throws IOException {

        List<String> otherHits = new CopyOnWriteArrayList<>();
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", exchange -> {
            otherHits.add(exchange.getRequestURI().getPath());
            write(exchange, ROBOTS.equals(exchange.getRequestURI().getPath()) ? 404 : 200, "other");
        });
        other.start();
        try {
            String otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
            serve(ROBOTS, 200, "User-agent: *\nDisallow: /private\n");
            serve("/a", 200, "a");
            PoliteFetchSession session = session(null, null);

            session.get(URI.create(base + "/a"));
            String body = session.get(URI.create(otherBase + "/private/1"));

            assertThat(body).isEqualTo("other");
            assertThat(otherHits).containsExactly(ROBOTS, "/private/1");
            assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        } finally {
            other.stop(0);
        }

    }

    @Test
    @DisplayName("robots.txt 가 막은 경로는 요청하지 않는다")
    void disallowedPathIsNotRequested(CapturedOutput output) {

        serve(ROBOTS, 200, "User-agent: *\nDisallow: /private\n");
        serve("/private/1", 200, "secret");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/private/1")));

        assertThat(code).isEqualTo(FetchFailureCode.ROBOTS_DISALLOWED);
        assertThat(hits).containsExactly(ROBOTS);
        assertThat(output).contains("robots.txt 가 막아 건너뜀: " + base.substring("http://".length()) + "/private/1");

    }

    @Test
    @DisplayName("429 는 HTTP_429 로 구분한다")
    void tooManyRequestsHasOwnCode() {

        serve("/jobs", 429, "slow down");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/jobs")));

        assertThat(code).isEqualTo(FetchFailureCode.HTTP_429);

    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 500, 204})
    @DisplayName("200 이 아닌 다른 상태는 HTTP_STATUS 로 실패한다")
    void otherStatusFails(int status) {

        serve("/jobs", status, "");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/jobs")));

        assertThat(code).isEqualTo(FetchFailureCode.HTTP_STATUS);

    }

    @Test
    @DisplayName("넘겨 주는 응답은 따라가지 않고 실패한다")
    void redirectIsNotFollowed() {

        routes.put("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new");
            write(exchange, 302, "");
        });
        serve("/new", 200, "moved");

        FetchFailureCode code = failureOf(() -> session(null, null).get(URI.create(base + "/old")));

        assertThat(code).isEqualTo(FetchFailureCode.HTTP_STATUS);
        assertThat(hits).doesNotContain("/new");

    }

    @Test
    @DisplayName("응답 본문이 오다 멈춰도 읽기 제한 시간이 지나면 TIMEOUT 으로 실패한다")
    void stalledBodyTimesOut() {

        routes.put("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write("partial".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepQuietly(3000);
        });
        PoliteFetchSession session = session(Duration.ofMillis(500), null);
        long started = System.nanoTime();

        FetchFailureCode code = failureOf(() -> session.get(URI.create(base + "/slow")));

        assertThat(code).isEqualTo(FetchFailureCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

    }

    @Test
    @DisplayName("본문이 크기 상한을 넘으면 TOO_LARGE 로 실패하고 상한과 같은 크기는 받는다")
    void bodyOverLimitFails() {

        serve("/exact", 200, "a".repeat(1000));
        serve("/over", 200, "a".repeat(1001));
        PoliteFetchSession session = session(null, 1000L);

        String exact = session.get(URI.create(base + "/exact"));
        FetchFailureCode code = failureOf(() -> session.get(URI.create(base + "/over")));

        assertThat(exact).hasSize(1000);
        assertThat(code).isEqualTo(FetchFailureCode.TOO_LARGE);

    }

    @Test
    @DisplayName("요청마다 설정한 User-Agent 를 보낸다")
    void sendsConfiguredUserAgent() {

        serve("/jobs", 200, "ok");

        session(null, null).get(URI.create(base + "/jobs"));

        assertThat(userAgents).hasSize(2).containsOnly(
                "Gitory/1.0 (+https://github.com/kakaotechcampus-4/ktc4-kyungpook-1)");

    }

    @Test
    @DisplayName("응답에 다른 문자 집합이 적혀 있어도 UTF-8 로 읽는다")
    void readsBodyAsUtf8() {

        routes.put("/ko", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=ISO-8859-1");
            write(exchange, 200, "백엔드 개발자 채용");
        });

        String body = session(null, null).get(URI.create(base + "/ko"));

        assertThat(body).isEqualTo("백엔드 개발자 채용");

    }

    @Test
    @DisplayName("http 나 https 가 아닌 주소는 받지 않는다")
    void rejectsNonWebAddress() {

        Throwable thrown = catchThrowable(() -> session(null, null).get(URI.create("file:///etc/passwd")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);

    }

    private PoliteFetchSession session(Duration readTimeout, Long maxBodyBytes) {

        PoliteFetchProperties properties = new PoliteFetchProperties(null, readTimeout, maxBodyBytes, null, null, null);
        PoliteFetcher fetcher = new PoliteFetcher(properties, clock, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        });
        return fetcher.open();

    }

    private void serve(String path, int status, String body) {

        routes.put(path, exchange -> write(exchange, status, body));

    }

    private void redirect(String path, String location) {

        routes.put(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            write(exchange, 302, "");
        });

    }

    private static void write(HttpExchange exchange, int status, String body)
            throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();

    }

    private static FetchFailureCode failureOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {

        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PoliteFetchException.class);
        return ((PoliteFetchException) thrown).code();

    }

    private static void sleepQuietly(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private static final class FakeClock extends Clock {

        private long millis;

        void advance(Duration duration) {

            millis += duration.toMillis();

        }

        @Override
        public long millis() {

            return millis;

        }

        @Override
        public Instant instant() {

            return Instant.ofEpochMilli(millis);

        }

        @Override
        public ZoneId getZone() {

            return ZoneOffset.UTC;

        }

        @Override
        public Clock withZone(ZoneId zone) {

            return this;

        }
    }
}
