package com.gitory.backend.matching.infra;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 회사 채용 사이트 대신 쓰는 로컬 서버로, 경로(쿼리 포함)마다 응답을 정하고 받은 요청을 순서대로 남긴다
 * 정하지 않은 경로는 404 라서 robots.txt 는 따로 정하지 않으면 '막는 규칙 없음' 이다
 */
final class FakeCareersSite implements AutoCloseable {

    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    private FakeCareersSite(HttpServer server) {
        this.server = server;
    }

    static FakeCareersSite start() {

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeCareersSite site = new FakeCareersSite(server);
            server.createContext("/", exchange -> {
                String target = exchange.getRequestURI().getRawPath()
                        + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
                site.hits.add(exchange.getRequestMethod() + " " + target);
                byte[] body = site.bodies.getOrDefault(target, "").getBytes(StandardCharsets.UTF_8);
                int status = site.statuses.getOrDefault(target, site.bodies.containsKey(target) ? 200 : 404);
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                exchange.close();
            });
            server.start();
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    String base() {

        return "http://127.0.0.1:" + server.getAddress().getPort();

    }

    void put(String target, String body) {

        bodies.put(target, body);

    }

    void fail(String target, int status) {

        statuses.put(target, status);

    }

    /** robots.txt 요청은 빼고 받은 요청을 "메서드 경로" 로 돌려준다 */
    List<String> hits() {

        return hits.stream().filter(hit -> !hit.endsWith("/robots.txt")).toList();

    }

    static PoliteFetchSession session() {

        PoliteFetchProperties properties = new PoliteFetchProperties(null, null, null, Duration.ZERO, null, null);
        return new PoliteFetcher(properties, Clock.systemUTC()).open();

    }

    @Override
    public void close() {

        server.stop(0);

    }
}
