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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 회사 채용 사이트 대신 쓰는 로컬 서버로, 경로(쿼리 포함)마다 응답을 정하고 받은 요청을 순서대로 남긴다
 * 정하지 않은 경로는 404 라서 robots.txt 는 따로 정하지 않으면 '막는 규칙 없음' 이다
 */
final class FakeCareersSite implements AutoCloseable {

    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final List<String> posted = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<String>> sequences = new ConcurrentHashMap<>();
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
                if (!target.endsWith("/robots.txt")) {
                    site.requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    site.posted.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                Queue<String> sequence = site.sequences.get(target);
                String next = sequence == null ? null : sequence.poll();
                String text = next != null ? next : site.bodies.getOrDefault(target, "");
                byte[] body = text.getBytes(StandardCharsets.UTF_8);
                int status = site.statuses.getOrDefault(target, next != null || site.bodies.containsKey(target) ? 200 : 404);
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

    /** 같은 주소에 온 요청마다 차례로 다른 응답을 주고, 다 쓰면 put 으로 정한 응답이나 404 를 준다 */
    void putInOrder(String target, String... responses) {

        sequences.put(target, new ConcurrentLinkedQueue<>(List.of(responses)));

    }

    List<String> posted() {

        return List.copyOf(posted);

    }

    /** robots.txt 요청은 빼고, 받은 요청마다 그 헤더의 첫 값(없으면 빈 글)을 차례로 돌려준다 */
    List<String> headerValues(String name) {

        return requestHeaders.stream().map(headers -> headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(entry -> entry.getValue().getFirst()).findFirst().orElse(""))
                .toList();

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
