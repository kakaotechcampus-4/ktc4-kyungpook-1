package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.ExtractedCriterion;
import com.gitory.backend.agent.port.ExtractedExperience;
import com.gitory.backend.agent.port.JobPostingExtractionRequest;
import com.gitory.backend.agent.port.JobPostingExtractionResult;
import com.gitory.backend.agent.port.PostingContentStatus;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.agent.port.PostingEducationLevel;
import com.gitory.backend.agent.port.PostingExperienceLevel;
import com.gitory.backend.agent.port.PostingExtractionOutcome;
import com.gitory.backend.agent.port.PostingExtractionUnavailableException;
import com.gitory.backend.agent.port.PostingGeneralType;
import com.gitory.backend.agent.port.PostingJobCategory;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** 실제 HTTP 를 거쳐 공고 조건 뽑기 API 의 요청 모양과 결과 세 가지, 응답 검사, 실패 구분을 확인한다 */
class AiJobPostingExtractionAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/job-postings/extract";
    private static final String HASH = "2342bcdd05db594d14eb7eadcc6eff81f6c74d4188d8cafd831a3f06846af281";
    private static final String PREVIOUS_HASH = "a".repeat(64);
    private static final String REQUEST_TEXT = "request-only-text";
    private static final String RESPONSE_TEXT = "response-only-text";

    private HttpServer server;
    private AiJobPostingExtractionAdapter adapter;
    private volatile String responseBody;
    private volatile String responseContentType = "application/json";
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = success(updatedData());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            exchange.sendResponseHeaders(responseStatus, body.length);
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
    @DisplayName("이전 해시와 상태와 뽑기 버전이 있으면 본문과 함께 계약 모양 그대로 보낸다")
    void sendsPreviousHashAndStatus() {

        adapter.extract(new JobPostingExtractionRequest("acme", "backend-101", "백엔드 개발자",
                "https://careers.example.com/jobs/backend-101", REQUEST_TEXT, PREVIOUS_HASH,
                PostingContentStatus.METADATA_ONLY, 2));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(PATH);
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "source": "acme",
                  "external_posting_id": "backend-101",
                  "job_title": "백엔드 개발자",
                  "source_url": "https://careers.example.com/jobs/backend-101",
                  "raw_text": "request-only-text",
                  "previous_content_hash": "%s",
                  "previous_content_status": "METADATA_ONLY",
                  "previous_extraction_version": 2
                }
                """.formatted(PREVIOUS_HASH)));

    }

    @Test
    @DisplayName("이전 해시와 상태와 뽑기 버전이 없으면 세 칸을 아예 보내지 않아, 모르는 칸을 거절하는 AI 와도 맞는다")
    void omitsPreviousFieldsWhenAbsent() {

        adapter.extract(request(null, null));

        assertThat(MAPPER.readTree(requestBody).propertyNames()).containsExactlyInAnyOrder("source",
                "external_posting_id", "job_title", "source_url", "raw_text");

    }

    @Test
    @DisplayName("UPDATED 응답에서 조건과 직무 분류와 경력과 해시를 읽고, 새 칸이 없으면 빈 목록과 빈 값으로 둔다")
    void readsUpdatedResult() {

        JobPostingExtractionResult result = adapter.extract(request(null, null));

        assertThat(result).isEqualTo(JobPostingExtractionResult.updated(HASH, PostingContentStatus.READY, List.of(
                new ExtractedCriterion(PostingCriterionType.RESPONSIBILITY, "Spring Boot 기반 REST API 개발",
                        "Spring Boot 기반 REST API를 설계하고 개발합니다."),
                new ExtractedCriterion(PostingCriterionType.REQUIRED, "Java 개발 경험", "Java 개발 경험이 필요합니다.")),
                PostingJobCategory.BACKEND,
                new ExtractedExperience(PostingExperienceLevel.EXPERIENCED, 3, "경력 3년 이상"), List.of(), null));

    }

    @Test
    @DisplayName("조건의 기술·일반 조건 종류·학력 수준·예외와 공고의 팀 기술·뽑기 버전을 읽고, 기술 이름은 목록과 견주지 않고 그대로 넘긴다")
    void readsTechsGeneralTypeEducationAndVersion() {

        responseBody = success(updated(data -> {
            data.put("extraction_version", 2);
            data.putArray("team_techs").add("KAFKA").add("COBOL");
            criterion(data, 1).putArray("techs").add("JAVA").add("COBOL");
            ((ArrayNode) data.path("criteria")).addObject().put("criterion_type", "REQUIRED")
                    .put("general_type", "EDUCATION").put("text", "학사 이상 또는 그에 준하는 경력")
                    .put("source_quote", "학사 이상 또는 그에 준하는 경력").put("education_level", "BACHELOR")
                    .put("education_exempt", true);
        }));

        JobPostingExtractionResult result = adapter.extract(request(null, null));

        assertThat(result.teamTechs()).containsExactly("KAFKA", "COBOL");
        assertThat(result.extractionVersion()).isEqualTo(2);
        assertThat(result.criteria().get(1).techs()).containsExactly("JAVA", "COBOL");
        assertThat(result.criteria().get(2)).isEqualTo(new ExtractedCriterion(PostingCriterionType.REQUIRED,
                "학사 이상 또는 그에 준하는 경력", "학사 이상 또는 그에 준하는 경력", PostingGeneralType.EDUCATION, List.of(),
                PostingEducationLevel.BACHELOR, true));

    }

    @Test
    @DisplayName("일반 조건 종류가 여섯 가지 밖의 값이면 기타(OTHER)로 읽는다")
    void readsUnknownGeneralTypeAsOther() {

        responseBody = success(updated(data -> criterion(data, 1).put("general_type", "VISA")));

        assertThat(adapter.extract(request(null, null)).criteria().get(1).generalType())
                .isEqualTo(PostingGeneralType.OTHER);

    }

    @Test
    @DisplayName("학력 수준과 예외는 학력 조건에만 읽고, 다른 조건에 붙었거나 모르는 수준이면 비워 거르지 않게 한다")
    void readsEducationOnlyOnEducationCriteria() {

        responseBody = success(updated(data -> {
            criterion(data, 0).put("education_level", "BACHELOR").put("education_exempt", true);
            criterion(data, 1).put("general_type", "EDUCATION").put("education_level", "BOOTCAMP");
        }));

        List<ExtractedCriterion> criteria = adapter.extract(request(null, null)).criteria();

        assertThat(criteria).extracting(ExtractedCriterion::educationLevel, ExtractedCriterion::educationExempt)
                .containsExactly(tuple(null, false), tuple(null, false));

    }

    @Test
    @DisplayName("UNCHANGED 응답은 해시와 상태만 읽고 조건과 분류와 경력은 비워 둔다")
    void readsUnchangedResult() {

        responseBody = success(unchangedData());

        JobPostingExtractionResult result = adapter.extract(request(HASH, PostingContentStatus.READY));

        assertThat(result).isEqualTo(JobPostingExtractionResult.unchanged(HASH, PostingContentStatus.READY));

    }

    @Test
    @DisplayName("근거를 원문에서 확인할 수 없다는 거절(422)은 실패가 아니라 REJECTED 결과로 돌려준다")
    void returnsRejectedForEvidenceMismatch() {

        responseStatus = 422;
        responseBody = failure("EVIDENCE_MISMATCH", false);

        JobPostingExtractionResult result = adapter.extract(request(null, null));

        assertThat(result.outcome()).isEqualTo(PostingExtractionOutcome.REJECTED);
        assertThat(result.criteria()).isEmpty();
        assertThat(result.contentHash()).isNull();

    }

    @ParameterizedTest
    @CsvSource({"503, LLM_UNAVAILABLE, true, true, false", "503, LLM_UNAVAILABLE, false, false, true",
            "400, INVALID_PAYLOAD, false, false, false", "500, INTERNAL_ERROR, true, false, false"})
    @DisplayName("AI 가 다시 해도 된다고 알린 실패만 다시 시도할 수 있고, 다시 해도 같은 LLM 오류는 따로 알린다")
    void classifiesFailure(int status, String code, boolean envelopeRetryable, boolean retryable, boolean permanent) {

        responseStatus = status;
        responseBody = failure(code, envelopeRetryable);

        assertFailure(retryable, code, permanent);

    }

    @ParameterizedTest
    @MethodSource("malformedResults")
    @DisplayName("계약과 다른 응답은 다시 시도할 수 없는 이상한 응답으로 끝난다")
    void rejectsMalformedResult(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false, "AI_INVALID_RESPONSE", false);

    }

    @Test
    @DisplayName("직무 분류가 아홉 가지 밖의 값이면 기타로 읽는다")
    void readsUnknownCategoryAsEtc() {

        responseBody = success(updated(data -> data.put("job_category", "GAME_CLIENT")));

        assertThat(adapter.extract(request(null, null)).category()).isEqualTo(PostingJobCategory.ETC);

    }

    @Test
    @DisplayName("조건 뽑기 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByPostingExtractReadTimeout() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().postingExtractReadTimeout(Duration.ofMillis(100)).build());

        assertFailure(true, "AI_UNAVAILABLE", false);

    }

    @Test
    @DisplayName("응답의 글이 든 예외는 원인으로 넘기지 않아, 모르는 칸 값이 예외 메시지로 새지 않는다")
    void doesNotLeakResponseTextThroughTheCause() {

        responseBody = success(updated(data -> data.put("content_status", RESPONSE_TEXT)));

        assertFailure(false, "AI_INVALID_RESPONSE", false);

    }

    @Test
    @DisplayName("UNCHANGED 인데 조건이 들어 있으면 이상한 응답으로 끝난다")
    void rejectsUnchangedWithCriteria() {

        responseBody = success(updated(data -> data.put("outcome", "UNCHANGED")));

        assertThatThrownBy(() -> adapter.extract(request(HASH, PostingContentStatus.READY)))
                .isInstanceOfSatisfying(PostingExtractionUnavailableException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("AI_INVALID_RESPONSE"));

    }

    @Test
    @DisplayName("이전 해시를 보내지 않았거나 해시가 다른데 UNCHANGED 가 오면 이상한 응답으로 끝난다")
    void rejectsUnchangedWithoutMatchingPreviousHash() {

        responseBody = success(unchangedData());

        assertFailure(false, "AI_INVALID_RESPONSE", false);
        assertThatThrownBy(() -> adapter.extract(request(PREVIOUS_HASH, PostingContentStatus.READY)))
                .isInstanceOfSatisfying(PostingExtractionUnavailableException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("AI_INVALID_RESPONSE"));

    }

    @Test
    @DisplayName("글자 수 상한은 UTF-16 단위가 아니라 코드 포인트로 센다")
    void countsCodePointsForTextLimit() {

        String emojis = Character.toString(0x1F600).repeat(600);
        responseBody = success(updated(data -> criterion(data, 0).put("text", emojis)));

        assertThat(adapter.extract(request(null, null)).criteria().get(0).text()).isEqualTo(emojis);

    }

    static Stream<Arguments> malformedResults() {

        return Stream.of(
                argumentSet("outcome 이 모르는 값이다", updated(data -> data.put("outcome", "CHANGED"))),
                argumentSet("content_hash 가 64자 소문자 16진수가 아니다", updated(data -> data.put("content_hash", "ABC"))),
                argumentSet("보낸 source 와 다르다", updated(data -> data.put("source", "other"))),
                argumentSet("보낸 external_posting_id 와 다르다", updated(data -> data.put("external_posting_id", "x"))),
                argumentSet("content_status 가 모르는 값이다", updated(data -> data.put("content_status", "PENDING"))),
                argumentSet("criteria 가 배열이 아니다", updated(data -> data.put("criteria", "없음"))),
                argumentSet("조건 종류가 모르는 값이다", updated(data -> criterion(data, 0).put("criterion_type", "SKILL"))),
                argumentSet("조건 text 가 비었다", updated(data -> criterion(data, 0).put("text", "  "))),
                argumentSet("조건 text 가 1000자를 넘는다", updated(data -> criterion(data, 0).put("text", "가".repeat(1001)))),
                argumentSet("조건 인용이 없다", updated(data -> criterion(data, 0).remove("source_quote"))),
                argumentSet("조건 인용이 4000자를 넘는다",
                        updated(data -> criterion(data, 0).put("source_quote", "가".repeat(4001)))),
                argumentSet("READY 인데 조건이 없다", updated(data -> data.putArray("criteria"))),
                argumentSet("METADATA_ONLY 인데 조건이 있다", updated(data -> data.put("content_status", "METADATA_ONLY"))),
                argumentSet("job_category 가 없다", updated(data -> data.remove("job_category"))),
                argumentSet("experience 가 없다", updated(data -> data.remove("experience"))),
                argumentSet("경력 level 이 모르는 값이다", updated(data -> experience(data).put("level", "SENIOR"))),
                argumentSet("경력 EXPERIENCED 에 연차가 없다", updated(data -> experience(data).putNull("min_years"))),
                argumentSet("경력 EXPERIENCED 연차가 0 이다", updated(data -> experience(data).put("min_years", 0))),
                argumentSet("경력 EXPERIENCED 연차가 60 을 넘는다", updated(data -> experience(data).put("min_years", 61))),
                argumentSet("경력 EXPERIENCED 에 인용이 없다", updated(data -> experience(data).putNull("source_quote"))),
                argumentSet("경력 ENTRY 에 연차가 있다", updated(data -> experience(data).put("level", "ENTRY"))),
                argumentSet("경력 ENTRY 에 인용이 없다", updated(data -> experience(data).put("level", "ENTRY")
                        .putNull("min_years").putNull("source_quote"))),
                argumentSet("경력 UNSPECIFIED 에 인용이 있다", updated(data -> experience(data).put("level", "UNSPECIFIED")
                        .putNull("min_years"))),
                argumentSet("경력 인용이 1000자를 넘는다",
                        updated(data -> experience(data).put("source_quote", "가".repeat(1001)))),
                argumentSet("조건 techs 가 배열이 아니다", updated(data -> criterion(data, 1).put("techs", "JAVA"))),
                argumentSet("조건 techs 에 글자가 아닌 값이 있다",
                        updated(data -> criterion(data, 1).putArray("techs").add(1))),
                argumentSet("team_techs 가 배열이 아니다", updated(data -> data.put("team_techs", "KAFKA"))),
                argumentSet("일반 조건 종류가 글자가 아니다", updated(data -> criterion(data, 1).put("general_type", 1))),
                argumentSet("학력 예외가 참·거짓이 아니다", updated(data -> criterion(data, 1)
                        .put("general_type", "EDUCATION").put("education_exempt", "true"))),
                argumentSet("뽑기 버전이 1보다 작다", updated(data -> data.put("extraction_version", 0))),
                argumentSet("뽑기 버전이 정수가 아니다", updated(data -> data.put("extraction_version", "2"))));

    }

    private static ObjectNode criterion(ObjectNode data, int index) {

        return (ObjectNode) data.path("criteria").path(index);

    }

    private static ObjectNode experience(ObjectNode data) {

        return (ObjectNode) data.path("experience");

    }

    private static ObjectNode updated(Consumer<ObjectNode> edit) {

        ObjectNode data = updatedData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode updatedData() {

        return (ObjectNode) MAPPER.readTree("""
                {
                  "source": "acme",
                  "external_posting_id": "backend-101",
                  "source_url": "https://careers.example.com/jobs/backend-101",
                  "content_hash": "%s",
                  "outcome": "UPDATED",
                  "content_status": "READY",
                  "criteria": [
                    {
                      "criterion_type": "RESPONSIBILITY",
                      "text": "Spring Boot 기반 REST API 개발",
                      "source_quote": "Spring Boot 기반 REST API를 설계하고 개발합니다."
                    },
                    {
                      "criterion_type": "REQUIRED",
                      "text": "Java 개발 경험",
                      "source_quote": "Java 개발 경험이 필요합니다."
                    }
                  ],
                  "job_category": "BACKEND",
                  "experience": {"level": "EXPERIENCED", "min_years": 3, "source_quote": "경력 3년 이상"}
                }
                """.formatted(HASH));

    }

    private static ObjectNode unchangedData() {

        ObjectNode data = updatedData();
        data.put("outcome", "UNCHANGED");
        data.putArray("criteria");
        data.putNull("job_category");
        data.putNull("experience");
        return data;

    }

    private static String success(ObjectNode data) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", true);
        envelope.set("data", data);
        envelope.set("meta", MAPPER.createObjectNode().putNull("model").put("tool_calls_made", 0)
                .put("processing_ms", 3));
        envelope.putNull("error");
        return MAPPER.writeValueAsString(envelope);

    }

    private static String failure(String code, boolean retryable) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", false).putNull("data");
        envelope.set("meta", MAPPER.createObjectNode());
        envelope.set("error", MAPPER.createObjectNode().put("code", code).put("message", RESPONSE_TEXT)
                .put("retryable", retryable));
        return MAPPER.writeValueAsString(envelope);

    }

    private static JobPostingExtractionRequest request(String previousHash, PostingContentStatus previousStatus) {

        return new JobPostingExtractionRequest("acme", "backend-101", "백엔드 개발자",
                "https://careers.example.com/jobs/backend-101", REQUEST_TEXT, previousHash, previousStatus, null);

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiJobPostingExtractionAdapter adapterOf(AiClientProperties properties) {

        return new AiJobPostingExtractionAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    /** 실패 구분과 오류 코드가 맞고, 요청·응답의 글은 예외 메시지와 원인 어디에도 없다 */
    private void assertFailure(boolean retryable, String code, boolean permanent) {

        assertThatThrownBy(() -> adapter.extract(request(null, null)))
                .isInstanceOfSatisfying(PostingExtractionUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    assertThat(failure.isInvalidResponse()).isEqualTo(!retryable);
                    assertThat(failure.getErrorCode()).isEqualTo(code);
                    assertThat(failure.isPermanentModelFailure()).isEqualTo(permanent);
                    for (Throwable chain = failure; chain != null; chain = chain.getCause()) {
                        assertThat(String.valueOf(chain.getMessage())).doesNotContain(REQUEST_TEXT, RESPONSE_TEXT);
                    }
                });

    }
}
