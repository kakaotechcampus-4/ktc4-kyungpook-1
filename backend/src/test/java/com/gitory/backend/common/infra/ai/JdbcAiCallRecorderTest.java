package com.gitory.backend.common.infra.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcAiCallRecorder.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcAiCallRecorderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcAiCallRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear() {

        jdbc.update("DELETE FROM ai_call");

    }

    @Test
    @DisplayName("AI 호출 한 번을 경로·Job·카드·상태와 AI 가 준 처리 기록의 칸마다 나눠 한 줄로 남긴다")
    void storesAiMetaInOneRow() throws Exception {

        recorder.record(new AiCall("/internal/analysis/star", 3L, 5L, 200, null, JsonMapper.builder().build().readTree(
                "{\"model\":\"gpt-5.6-luna\",\"llm_calls\":2,\"tool_calls_made\":1,\"repair_rounds\":1,"
                        + "\"input_tokens\":1800,\"output_tokens\":420,\"github_calls\":4,\"ended_by\":\"PARTIAL\","
                        + "\"processing_ms\":9300}"), 9450));

        assertThat(onlyRow()).containsEntry("path", "/internal/analysis/star")
                .containsEntry("job_id", 3L)
                .containsEntry("card_id", 5L)
                .containsEntry("http_status", 200)
                .containsEntry("error_code", null)
                .containsEntry("model", "gpt-5.6-luna")
                .containsEntry("llm_calls", 2)
                .containsEntry("tool_calls", 1)
                .containsEntry("repair_rounds", 1)
                .containsEntry("input_tokens", 1800)
                .containsEntry("output_tokens", 420)
                .containsEntry("github_calls", 4)
                .containsEntry("ended_by", "PARTIAL")
                .containsEntry("ai_processing_ms", 9300)
                .containsEntry("elapsed_ms", 9450L);

    }

    @Test
    @DisplayName("AI 가 준 처리 기록이 없으면 그 칸들은 비우고 상태·오류 코드·걸린 시간만 남긴다")
    void storesCallWithoutMetaWithEmptyMetaColumns() {

        recorder.record(new AiCall("/internal/collect", null, null, null, "AI_UNAVAILABLE", null, 12));

        assertThat(onlyRow()).containsEntry("http_status", null)
                .containsEntry("error_code", "AI_UNAVAILABLE")
                .containsEntry("model", null)
                .containsEntry("llm_calls", null)
                .containsEntry("ended_by", null)
                .containsEntry("elapsed_ms", 12L);

    }

    @Test
    @DisplayName("AI 가 준 모델별 호출 수·토큰 목록은 받은 순서 그대로 한 칸에 남는다")
    void storesModelUsageListAsIs() throws Exception {

        String usage = "[{\"model\":\"luna\",\"llm_calls\":3,\"input_tokens\":9000,\"output_tokens\":2000},"
                + "{\"model\":\"sol\",\"llm_calls\":1,\"input_tokens\":4000,\"output_tokens\":300}]";

        recorder.record(new AiCall("/internal/analysis/star", 3L, 5L, 200, null,
                JSON.readTree("{\"model\":\"sol\",\"llm_calls\":4,\"model_usage\":" + usage + "}"), 100));

        assertThat(JSON.readTree(jdbc.queryForObject("SELECT model_usage::text FROM ai_call", String.class)))
                .isEqualTo(JSON.readTree(usage));

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"model_usage\":[]}", "{\"llm_calls\":0}", "{\"model_usage\":{\"model\":\"luna\"}}",
            "{\"model_usage\":\"luna\"}"})
    @DisplayName("모델별 목록이 비었거나 없거나 배열이 아니면 그 칸은 비운다")
    void leavesModelUsageEmptyWhenNotANonEmptyList(String meta) throws Exception {

        recorder.record(new AiCall("/internal/analysis/star", null, null, 200, null, JSON.readTree(meta), 10));

        assertThat(onlyRow()).containsEntry("model_usage", null);

    }

    @Test
    @DisplayName("부른 쪽 트랜잭션이 롤백돼도 기록은 따로 커밋돼 남는다")
    void keepsRecordWhenCallerRollsBack() {

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(new AiCall("/internal/analysis/groups", 1L, null, 503, "LLM_UNAVAILABLE", null, 40));
            status.setRollbackOnly();
        });

        assertThat(onlyRow()).containsEntry("error_code", "LLM_UNAVAILABLE");

    }

    private Map<String, Object> onlyRow() {

        return jdbc.queryForMap("SELECT * FROM ai_call");

    }
}
