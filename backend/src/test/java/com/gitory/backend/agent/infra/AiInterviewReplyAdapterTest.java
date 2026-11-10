package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewReplyExtra;
import com.gitory.backend.agent.port.InterviewReplyRequest;
import com.gitory.backend.agent.port.InterviewReplyResult;
import com.gitory.backend.agent.port.InterviewReplySlot;
import com.gitory.backend.agent.port.InterviewReplyVerdict;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 HTTP 를 거쳐 되묻기 답 판단 API 의 요청 모양과 응답 읽기를 공유 계약 사례와 견준다 */
class AiInterviewReplyAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode CONTRACT = MAPPER.readTree(Path.of("../contracts/interview/reply.json"));
    private static final String PATH = "/internal/interview-replies";

    private HttpServer server;
    private AiInterviewReplyAdapter adapter;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = MAPPER.writeValueAsString(CONTRACT.path("aiToSpring").path("applied"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        adapter = new AiInterviewReplyAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("공유 계약 사례와 같은 경로·같은 JSON 을 보내고, 계약의 APPLY 응답을 문장과 다른 칸 문장까지 읽는다")
    void sendsTheContractRequestAndReadsTheContractResponse() {

        InterviewReplyResult result = adapter.reply(request());

        assertThat(requestPath).isEqualTo(PATH);
        assertThat((Object) MAPPER.readTree(requestBody))
                .isEqualTo(CONTRACT.path("springToAi").path("expectedRequest"));
        assertThat(result).isEqualTo(new InterviewReplyResult(InterviewReplyVerdict.APPLY,
                "배포 로그를 보고 매번 전체를 다시 빌드하는 것을 확인해, 바뀐 모듈만 다시 빌드하도록 설정을 고쳤다.",
                List.of(new InterviewReplyExtra(InterviewStarSlot.R, "배포 시간이 12분에서 4분으로 줄었다.")), null,
                null));

    }

    @ParameterizedTest
    @ValueSource(strings = {"askMore", "offTopic", "dontKnow"})
    @DisplayName("계약의 나머지 응답(더 묻기·상관없음·모름)을 판단 종류와 꼬리 질문·이유로 읽는다")
    void readsTheOtherContractResponses(String name) {

        responseBody = MAPPER.writeValueAsString(CONTRACT.path("aiToSpring").path(name));
        JsonNode data = CONTRACT.path("aiToSpring").path(name).path("data");

        InterviewReplyResult result = adapter.reply(request());

        assertThat(result.verdict().name()).isEqualTo(data.path("verdict").asString());
        assertThat(result.sentence()).isNull();
        assertThat(result.extra()).isEmpty();
        assertThat(result.followUp()).isEqualTo(data.path("follow_up").isNull() ? null : data.path("follow_up").asString());
        assertThat(result.reason()).isEqualTo(data.path("reason").isNull() ? null : data.path("reason").asString());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":true,\"data\":{\"card_id\":999,\"verdict\":\"APPLY\",\"sentence\":\"문장\"},\"error\":null}",
            "{\"success\":true,\"data\":{\"card_id\":104,\"verdict\":\"MAYBE\"},\"error\":null}",
            "{\"success\":true,\"data\":{\"card_id\":104,\"verdict\":\"APPLY\",\"extra\":[{\"star_slot\":\"X\",\"sentence\":\"문장\"}]},\"error\":null}"
    })
    @DisplayName("다른 카드의 응답이거나 모르는 판단·모르는 칸이면 응답이 이상한 실패로 던진다")
    void rejectsResponsesThatBreakTheContract(String body) {

        responseBody = body;

        assertThatThrownBy(() -> adapter.reply(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isInvalidResponse()).isTrue();
                    assertThat(failure.isRetryable()).isFalse();
                });

    }

    private static InterviewReplyRequest request() {

        return new InterviewReplyRequest(104, "배포 시간 줄이기", InterviewStarSlot.A,
                "배포가 늦어질 때 직접 하신 일이 무엇이었나요?",
                "로그를 보니까 매번 전체를 다시 빌드하고 있길래 바뀐 모듈만 다시 빌드하게 설정을 고쳤어요. 그 뒤로 12분에서 4분으로 줄었어요",
                List.of("설정을 좀 고쳤어요"),
                List.of(new InterviewReplySlot(InterviewStarSlot.S, "배포가 늦어져 팀이 수정 사항을 몰아서 올리던 때였다"),
                        new InterviewReplySlot(InterviewStarSlot.T, "배포 시간을 줄여야 했다"),
                        new InterviewReplySlot(InterviewStarSlot.A, null),
                        new InterviewReplySlot(InterviewStarSlot.R, null)),
                false);

    }
}
