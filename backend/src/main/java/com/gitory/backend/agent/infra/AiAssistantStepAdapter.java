package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.AssistantStepPort;
import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.agent.port.AssistantStepRequest;
import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AI 홈 한 줄 명령 API(다음 행동 고르기)를 부르고, 응답이 실행에 넘겨도 되는 모양인지 확인한다
 * 도구 이름이 없는 도구 호출, 말이 빈 질문, 모르는 행동은 다시 불러도 같은 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiAssistantStepAdapter implements AssistantStepPort {

    private static final String PATH = "/internal/assistant/next-step";

    // AI 가 25초 안에 답하므로 그 답을 놓치지 않게 조금 더 기다린다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> ASK_KINDS = Set.of("QUESTIONS", "CHOICE", "FREE");

    private final AiHttpClient ai;

    @Override
    public AssistantStepReply next(AssistantStepRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return reply(data);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static AssistantStepReply reply(JsonNode data) {

        String say = optionalString(data, "say");

        return switch (enumOf(data, "action", AssistantStepReply.Action.class)) {
            case CALL_TOOL -> AssistantStepReply.call(enumOf(data, "tool", AssistantTool.class),
                    find(data.path("find")), optionalString(data, "posting_ref"), optionalString(data, "repo_ref"),
                    optionalString(data, "card_ref"), optionalString(data, "letter_ref"),
                    optionalString(data, "job_role"), questions(data.path("questions")));
            case ASK_USER -> ask(data, say);
            case FINISH -> AssistantStepReply.finish(say);
        };

    }

    private static AssistantStepReply ask(JsonNode data, String say) {

        JsonNode kind = data.path("ask_kind");
        if (say == null || !kind.isString() || !ASK_KINDS.contains(kind.asString())) {
            throw AiClientException.invalidResponse();
        }

        return AssistantStepReply.ask(say, kind.asString(), strings(data.path("options")));

    }

    private static AssistantStepReply.Find find(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw AiClientException.invalidResponse();
        }

        return new AssistantStepReply.Find(optionalString(node, "company"), strings(node.path("keywords")));

    }

    private static List<AssistantStepReply.Question> questions(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw AiClientException.invalidResponse();
        }
        List<AssistantStepReply.Question> questions = new ArrayList<>();
        for (JsonNode question : node) {
            questions.add(new AssistantStepReply.Question(requiredString(question, "text"),
                    optionalInt(question, "char_limit")));
        }

        return List.copyOf(questions);

    }

    private static List<String> strings(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw AiClientException.invalidResponse();
        }
        List<String> strings = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString() || item.asString().isBlank()) {
                throw AiClientException.invalidResponse();
            }
            strings.add(item.asString().strip());
        }

        return List.copyOf(strings);

    }

    private static <E extends Enum<E>> E enumOf(JsonNode node, String field, Class<E> type) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        try {
            return Enum.valueOf(type, value.asString());
        } catch (IllegalArgumentException unknown) {
            throw AiClientException.invalidResponse();
        }

    }

    private static String requiredString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw AiClientException.invalidResponse();
        }

        return value.asString();

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }

        return value.asString().isBlank() ? null : value.asString().strip();

    }

    private static Integer optionalInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw AiClientException.invalidResponse();
        }

        return value.intValue();

    }

    // AI 는 LLM 이 실패하면 503 을 돌려주므로, 그 실패는 다시 해 볼 만하다
    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = failure.temporary();
        return new AgentUnavailableException("AI 홈 한 줄 명령 다음 행동 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure);

    }
}
