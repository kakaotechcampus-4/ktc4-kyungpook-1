package com.gitory.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.function.ThrowingConsumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 되묻기 질문을 돌려주는 가짜 AI 로, 받은 요청 본문을 모아 두고 응답 전에 테스트가 끼어들 자리를 준다
 * 요청마다 스레드를 따로 써서, 응답을 붙들어 둔 요청이 있어도 다른 요청을 받는다
 */
public final class FakeInterviewAi implements AutoCloseable {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ThrowingConsumer<JsonNode> NOTHING = request -> {
    };

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, Reply> reply = FakeInterviewAi::askAgain;
    private volatile ThrowingConsumer<JsonNode> beforeReply = NOTHING;

    private FakeInterviewAi(HttpServer server) {
        this.server = server;
    }

    public static FakeInterviewAi start() {

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeInterviewAi ai = new FakeInterviewAi(server);
            server.setExecutor(ai.executor);
            server.createContext("/", ai::handle);
            server.start();

            return ai;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }

    }

    public String baseUrl() {

        return "http://127.0.0.1:" + server.getAddress().getPort();

    }

    /** 지금까지 받은 요청 본문을 받은 순서대로 돌려준다 */
    public List<JsonNode> requests() {

        return List.copyOf(requests);

    }

    public void replyWith(Function<JsonNode, Reply> reply) {

        this.reply = reply;

    }

    /** 요청을 받은 뒤 응답하기 전에 실행하고, 끝날 때까지 응답을 붙들어 둔다 */
    public void beforeReply(ThrowingConsumer<JsonNode> hook) {

        this.beforeReply = hook;

    }

    /** 받은 요청을 비우고 응답과 끼어들 자리를 처음 모양으로 돌린다 */
    public void reset() {

        requests.clear();
        reply = FakeInterviewAi::askAgain;
        beforeReply = NOTHING;

    }

    @Override
    public void close() {

        server.stop(0);
        executor.shutdownNow();

    }

    private void handle(HttpExchange exchange) throws IOException {

        JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(request);

        Reply answer;
        try {
            beforeReply.accept(request);
            answer = reply.apply(request);
        } catch (Throwable failure) {
            answer = new Reply(500, "{\"success\":false,\"data\":null,\"meta\":{},\"error\":null}");
        }

        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();

    }

    /** 지정한 칸이 있으면 그 칸, 없으면 요청의 첫 고를 수 있는 칸에 질문하는 ASK_AGAIN 응답 */
    public static Reply askAgain(JsonNode request) {

        JsonNode target = request.path("target_star_slot");
        String slot = target.isString() ? target.asString()
                : request.path("missing_slots").path(0).path("star_slot").asString();

        return askAgain(request.path("card_id").longValue(), slot, "EVIDENCE_GAP", "AUTO",
                "이 칸에서 확인된 결과가 있었나요?");

    }

    public static Reply askAgain(long cardId, String slot, String questionType, String triggerSource,
                                 String questionText) {

        ObjectNode data = MAPPER.createObjectNode().put("card_id", cardId).put("target_star_slot", slot)
                .put("target_statement_seq", 1).put("question_type", questionType)
                .put("trigger_source", triggerSource).put("question_text", questionText)
                .put("next_action", "ASK_AGAIN");

        return success(data);

    }

    public static Reply complete(long cardId) {

        ObjectNode data = MAPPER.createObjectNode().put("card_id", cardId).putNull("target_star_slot")
                .putNull("target_statement_seq").putNull("question_type").putNull("trigger_source")
                .putNull("question_text").put("next_action", "COMPLETE");

        return success(data);

    }

    /** AI 의 오류 봉투로, 다시 시도해도 되는지는 retryable 이 정한다 */
    public static Reply failure(int status, String code, boolean retryable) {

        ObjectNode error = MAPPER.createObjectNode().put("code", code).put("message", "ai-only-text")
                .put("retryable", retryable);
        ObjectNode envelope = MAPPER.createObjectNode().put("success", false).putNull("data");
        envelope.set("meta", MAPPER.createObjectNode());
        envelope.set("error", error);

        return new Reply(status, MAPPER.writeValueAsString(envelope));

    }

    public static Reply success(ObjectNode data) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", true);
        envelope.set("data", data);
        envelope.set("meta", MAPPER.createObjectNode().putNull("model").put("tool_calls_made", 0)
                .put("processing_ms", 3));
        envelope.putNull("error");

        return new Reply(200, MAPPER.writeValueAsString(envelope));

    }

    public record Reply(int status, String body) {
    }
}
