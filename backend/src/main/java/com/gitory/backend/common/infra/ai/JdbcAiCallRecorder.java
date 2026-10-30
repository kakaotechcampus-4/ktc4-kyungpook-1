package com.gitory.backend.common.infra.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** AI 호출 기록을 부른 쪽 트랜잭션과 따로 커밋해, 부른 쪽이 롤백돼도 남게 한다 */
@Component
@RequiredArgsConstructor
class JdbcAiCallRecorder implements AiCallRecorder {

    private static final String INSERT = """
            INSERT INTO ai_call (path, job_id, card_id, cover_letter_id, http_status, error_code, model, llm_calls,
                                 tool_calls, repair_rounds, input_tokens, output_tokens, github_calls, ended_by,
                                 ai_processing_ms, elapsed_ms, model_usage)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """;

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void record(AiCall call) {

        JsonNode meta = call.meta() != null && call.meta().isObject() ? call.meta() : null;
        TransactionTemplate newTransaction = new TransactionTemplate(transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        newTransaction.executeWithoutResult(status -> jdbc.update(INSERT, call.path(), call.jobId(), call.cardId(),
                call.coverLetterId(), call.httpStatus(), call.errorCode(), textOf(meta, "model"), intOf(meta, "llm_calls"),
                intOf(meta, "tool_calls_made"), intOf(meta, "repair_rounds"), intOf(meta, "input_tokens"),
                intOf(meta, "output_tokens"), intOf(meta, "github_calls"), textOf(meta, "ended_by"),
                intOf(meta, "processing_ms"), call.elapsedMs(), modelUsageOf(meta)));

    }

    /** 기록 저장이 AI 호출을 실패시키면 안 되므로 목록 안의 값은 검사하지 않고, 비지 않은 배열만 그대로 남긴다 */
    private static String modelUsageOf(JsonNode meta) {

        JsonNode usage = meta == null ? null : meta.path("model_usage");
        return usage != null && usage.isArray() && !usage.isEmpty() ? usage.toString() : null;

    }

    private static String textOf(JsonNode meta, String field) {

        return meta != null && meta.path(field).isString() ? meta.path(field).asString() : null;

    }

    private static Integer intOf(JsonNode meta, String field) {

        return meta != null && meta.path(field).isIntegralNumber() ? meta.path(field).asInt() : null;

    }
}
