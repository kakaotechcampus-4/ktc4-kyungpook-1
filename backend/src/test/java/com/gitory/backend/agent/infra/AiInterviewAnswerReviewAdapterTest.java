package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewAnswerOutcome;
import com.gitory.backend.agent.port.InterviewAnswerReviewRequest;
import com.gitory.backend.agent.port.InterviewAnswerReviewResult;
import com.gitory.backend.agent.port.InterviewAnswerSource;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewCurrentTurn;
import com.gitory.backend.agent.port.InterviewFollowUp;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.agent.port.InterviewStatementTarget;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 HTTP 를 거쳐 답 평가 API 의 요청 모양과 응답 해석, 실패 구분을 검사한다 */
class AiInterviewAnswerReviewAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String INSUFFICIENT_BODY = """
            {"success": true, "error": null, "meta": {"model": null, "tool_calls_made": 0, "processing_ms": 3},
             "data": {"outcome": "INSUFFICIENT", "resulting_statement": null,
                      "remaining_slots": [{"star_slot": "A", "statement_seq": 1}],
                      "next_action": "ASK_AGAIN",
                      "next_turn": {"target": {"star_slot": "A", "statement_seq": 1}, "question_type": "FOLLOWUP",
                                    "trigger_source": "AUTO", "question_text": "어떤 방법으로 해결했나요?"}}}
            """;
    private static final String ANSWERED_BODY = """
            {"success": true, "error": null, "meta": {"model": null, "tool_calls_made": 0, "processing_ms": 3},
             "data": {"outcome": "ANSWERED",
                      "resulting_statement": {"star_slot": "A", "statement_seq": 1, "body": "인덱스를 추가했다", "confidence": "HIGH"},
                      "remaining_slots": [], "next_action": "COMPLETE", "next_turn": null}}
            """;

    private HttpServer server;
    private AiInterviewAnswerReviewAdapter adapter;
    private volatile String responseBody = INSUFFICIENT_BODY;
    private volatile long responseDelayMs;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        adapter = adapterOf(properties().build());

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("답 평가 경로로 AI 요청 스키마의 키 이름 그대로 보낸다")
    void sendsRequestWithTheAiKeys() {

        adapter.review(request());

        assertThat(requestPath).isEqualTo("/internal/interview-answer-evaluations");
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "card_id": 104,
                  "source_type": "DIRECT_CARD",
                  "candidate": null,
                  "current_turn": {
                    "target": {"star_slot": "A", "statement_seq": 1},
                    "question_type": "EVIDENCE_GAP",
                    "question_text": "무엇을 했나요?"
                  },
                  "answer_text": "고쳤다",
                  "answer_source": "TYPED",
                  "other_missing_slots": [],
                  "existing_turn_count": 1,
                  "max_turns": 2
                }
                """));

    }

    @Test
    @DisplayName("답이 모자라다는 평가는 같은 칸에 다시 물을 질문과 함께 돌려준다")
    void returnsFollowUpWhenAnswerIsShort() {

        InterviewAnswerReviewResult result = adapter.review(request());

        assertThat(result).isEqualTo(new InterviewAnswerReviewResult(InterviewAnswerOutcome.INSUFFICIENT,
                new InterviewFollowUp(new InterviewStatementTarget(InterviewStarSlot.A, 1),
                        InterviewQuestionType.FOLLOWUP, "어떤 방법으로 해결했나요?")));

    }

    @Test
    @DisplayName("답이 충분하다는 평가는 다시 물을 질문 없이 돌려준다")
    void returnsNoFollowUpWhenAnswerIsEnough() {

        responseBody = ANSWERED_BODY;

        InterviewAnswerReviewResult result = adapter.review(request());

        assertThat(result).isEqualTo(new InterviewAnswerReviewResult(InterviewAnswerOutcome.ANSWERED, null));

    }

    @Test
    @DisplayName("다시 묻는다면서 질문이 없는 응답은 응답이 이상한 실패로 끝난다")
    void rejectsAskAgainWithoutQuestion() {

        responseBody = INSUFFICIENT_BODY.replace("\"next_turn\": {", "\"unused\": {");

        assertThatThrownBy(() -> adapter.review(request())).isInstanceOf(AgentUnavailableException.class);

    }

    @Test
    @DisplayName("충분하다고 끝내면서 다시 물을 질문을 함께 보낸 응답은 응답이 이상한 실패로 끝난다")
    void rejectsCompleteWithQuestion() {

        responseBody = INSUFFICIENT_BODY.replace("\"ASK_AGAIN\"", "\"COMPLETE\"");

        assertThatThrownBy(() -> adapter.review(request())).isInstanceOf(AgentUnavailableException.class);

    }

    @Test
    @DisplayName("다시 물을 질문의 글이 비어 있으면 응답이 이상한 실패로 끝난다")
    void rejectsBlankQuestionText() {

        responseBody = INSUFFICIENT_BODY.replace("어떤 방법으로 해결했나요?", "  ");

        assertThatThrownBy(() -> adapter.review(request())).isInstanceOf(AgentUnavailableException.class);

    }

    @Test
    @DisplayName("응답이 되묻기 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByInterviewReadTimeout() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().interviewReadTimeout(Duration.ofMillis(100)).build());

        assertThatThrownBy(() -> adapter.review(request())).isInstanceOf(AgentUnavailableException.class);

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiInterviewAnswerReviewAdapter adapterOf(AiClientProperties properties) {

        return new AiInterviewAnswerReviewAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    private static InterviewAnswerReviewRequest request() {

        return new InterviewAnswerReviewRequest(104, InterviewCardSourceType.DIRECT_CARD, null,
                new InterviewCurrentTurn(new InterviewStatementTarget(InterviewStarSlot.A, 1),
                        InterviewQuestionType.EVIDENCE_GAP, "무엇을 했나요?"),
                "고쳤다", InterviewAnswerSource.TYPED, List.of(), 1, 2);

    }
}
