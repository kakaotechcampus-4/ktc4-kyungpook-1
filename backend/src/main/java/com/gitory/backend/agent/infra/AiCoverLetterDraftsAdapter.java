package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsListener;
import com.gitory.backend.agent.port.CoverLetterDraftsPort;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AI 자소서 초안 쓰기 API 를 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * '확인 필요' 표시가 없는 문장 번호를 가리키면 화면이 그 표시를 붙일 곳이 없어, 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterDraftsAdapter implements CoverLetterDraftsPort {

    private static final String PATH = "/internal/cover-letter-drafts";

    private static final Set<String> ITEMS = Set.of("SITUATION", "ACTION", "RESULT", "LEARNING", "TRIGGER",
            "JOB_LINK", "REASON");
    private static final Set<String> MARK_CODES = Set.of("UNSOURCED_NUMBER", "UNSOURCED_TERM", "REPEATED",
            "UNSUPPORTED_CLAIM", "OFF_TOPIC");
    private static final Set<String> STEP_CODES = Set.of("UNSOURCED_NUMBER", "UNSOURCED_TERM", "OVER_LIMIT",
            "UNDER_LENGTH", "MISSING_BLANK", "REPEATED", "UNSUPPORTED_CLAIM", "OFF_TOPIC");
    private static final Set<String> SOURCES = Set.of("LLM", "SKELETON");
    private static final Set<String> ACTORS = Set.of("SUB_AGENT", "SUPERVISOR", "CODE");
    private static final Set<String> KINDS = Set.of("WROTE", "CHECK_PASSED", "CHECK_FAILED", "DECIDED", "REWROTE",
            "REVERTED", "MARKED", "SKELETON", "REVIEWED", "SENT_BACK", "FAILED");
    private static final Set<String> ACTIONS = Set.of("FIX", "KEEP");
    // AI 가 쓰는 도중의 글을 이 길이로 잘라 보내므로, 넘는 줄은 AI 가 잘못 보낸 것이다
    private static final int LIVE_TEXT_MAX_LENGTH = 5_000;

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public CoverLetterDrafts write(CoverLetterDraftsRequest request) {

        return write(request, null);

    }

    /** listener 가 없으면 글을 흘려보내지 않는 초안 쓰기 API 를 부른다 */
    @Override
    public CoverLetterDrafts write(CoverLetterDraftsRequest request, CoverLetterDraftsListener listener) {

        Set<Integer> questionIds = Set.copyOf(questionIdsOf(request));
        JsonNode data;
        try {
            data = listener == null
                    ? ai.post(PATH, request, properties.readTimeout())
                    : ai.postStreaming(PATH + "/stream", request, properties.readTimeout(),
                            progress -> forward(progress.liveItem(), questionIds, listener));
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return response(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    // 글·단계 줄은 화면에 보여 줄 값이라 모양이 틀린 줄만 버리고 초안은 계속 받는다
    private static void forward(JsonNode item, Set<Integer> questionIds, CoverLetterDraftsListener listener) {

        if (item == null) {
            return;
        }
        String kind = item.path("kind").asString("");
        if ("STEP".equals(kind)) {
            liveStep(item.path("step"), questionIds).ifPresent(listener::step);
            return;
        }
        JsonNode questionId = item.path("question_id");
        JsonNode round = item.path("round");
        JsonNode text = item.path("text");
        if ("DRAFT_TEXT".equals(kind) && questionId.isIntegralNumber() && questionId.canConvertToInt()
                && questionIds.contains(questionId.intValue()) && round.isIntegralNumber() && round.canConvertToInt()
                && round.intValue() >= 0 && text.isString() && text.asString().length() <= LIVE_TEXT_MAX_LENGTH) {
            listener.text(questionId.intValue(), round.intValue(), text.asString());
        }

    }

    private static Optional<CoverLetterDrafts.Step> liveStep(JsonNode node, Set<Integer> questionIds) {

        try {
            return Optional.of(step(node, questionIds));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

    }

    private static List<Integer> questionIdsOf(CoverLetterDraftsRequest request) {

        return request.questions().stream().map(CoverLetterDraftsRequest.Question::questionId).toList();

    }

    private static CoverLetterDrafts response(JsonNode data, CoverLetterDraftsRequest request) {

        List<Integer> sent = questionIdsOf(request);
        List<CoverLetterDrafts.Draft> drafts = new ArrayList<>();
        for (JsonNode node : array(data, "drafts")) {
            drafts.add(draft(node));
        }
        if (!drafts.stream().map(CoverLetterDrafts.Draft::questionId).toList().equals(sent)) {
            throw AiClientException.invalidResponse();
        }

        Set<Integer> questionIds = Set.copyOf(sent);
        List<CoverLetterDrafts.Step> steps = new ArrayList<>();
        for (JsonNode node : array(data, "steps")) {
            steps.add(step(node, questionIds));
        }

        return new CoverLetterDrafts(List.copyOf(drafts), List.copyOf(steps));

    }

    private static CoverLetterDrafts.Draft draft(JsonNode node) {

        List<CoverLetterDrafts.Sentence> sentences = new ArrayList<>();
        for (JsonNode sentence : array(node, "sentences")) {
            sentences.add(new CoverLetterDrafts.Sentence(positiveInt(sentence, "seq"), text(sentence, "text")));
        }

        List<CoverLetterDrafts.Mark> marks = new ArrayList<>();
        for (JsonNode mark : array(node, "marks")) {
            int sentenceSeq = positiveInt(mark, "sentence_seq");
            if (sentenceSeq > sentences.size()) {
                throw AiClientException.invalidResponse();
            }
            marks.add(new CoverLetterDrafts.Mark(sentenceSeq, oneOf(mark, "code", MARK_CODES),
                    optionalString(mark, "value")));
        }

        List<String> blanks = new ArrayList<>();
        for (JsonNode item : array(node, "blanks")) {
            blanks.add(item(item));
        }

        return new CoverLetterDrafts.Draft(positiveInt(node, "question_id"), text(node, "text"),
                List.copyOf(sentences), nonNegativeInt(node, "char_count"), nonNegativeInt(node, "over_by"),
                List.copyOf(marks), List.copyOf(blanks), oneOf(node, "source", SOURCES));

    }

    private static CoverLetterDrafts.Step step(JsonNode node, Set<Integer> questionIds) {

        Integer questionId = optionalPositiveInt(node, "question_id");
        if (questionId != null && !questionIds.contains(questionId)) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterDrafts.Step(positiveInt(node, "seq"), oneOf(node, "actor", ACTORS),
                oneOf(node, "kind", KINDS), questionId, optionalOneOf(node, "code", STEP_CODES),
                optionalString(node, "value"), optionalPositiveInt(node, "sentence_seq"),
                optionalString(node, "sentence"), optionalOneOf(node, "action", ACTIONS),
                optionalString(node, "reason"));

    }

    private static JsonNode array(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static int positiveInt(JsonNode node, String field) {

        int value = nonNegativeInt(node, field);
        if (value == 0) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static int nonNegativeInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static Integer optionalPositiveInt(JsonNode node, String field) {

        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : positiveInt(node, field);

    }

    private static String item(JsonNode value) {

        if (!value.isString() || !ITEMS.contains(value.asString())) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String text(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String oneOf(JsonNode node, String field, Set<String> allowed) {

        JsonNode value = node.path(field);
        if (!value.isString() || !allowed.contains(value.asString())) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String optionalOneOf(JsonNode node, String field, Set<String> allowed) {

        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : oneOf(node, field, allowed);

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

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 자소서 초안 쓰기 실패 (" + failure.errorCode() + ")", retryable,
                !retryable, failure);

    }

    // 연결이 안 되거나 시간이 지난 실패는 응답 상태와 상관없이 다시 해 볼 만하다
    // 400 은 우리 요청이 틀렸고 500 은 AI 가 고장 난 것이라 다시 불러도 같은 결과다
    private static boolean retryable(AiClientException failure) {

        Integer status = failure.httpStatus();
        if ("AI_UNAVAILABLE".equals(failure.errorCode())) {
            return true;
        }
        if (status != null && (status == 400 || status == 500)) {
            return false;
        }
        return failure.retryable();

    }
}
