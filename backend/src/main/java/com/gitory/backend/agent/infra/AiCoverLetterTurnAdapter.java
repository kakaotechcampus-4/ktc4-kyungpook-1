package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterFill;
import com.gitory.backend.agent.port.CoverLetterFillRequest;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.agent.port.CoverLetterTurnPort;
import com.gitory.backend.agent.port.CoverLetterTurnReply;
import com.gitory.backend.agent.port.CoverLetterTurnRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * AI 자소서 대화 API(다음 말)와 채우기 API(답으로 그 문항 다시 쓰기)를 부르고, 응답이 저장해도 되는 모양인지 확인한다
 * 다시 쓴 글은 초안 쓰기와 같은 허용 목록으로 보고, 다른 문항을 가리키거나 정리한 답 없이 채웠다고 하면 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiCoverLetterTurnAdapter implements CoverLetterTurnPort {

    private static final String TURN_PATH = "/internal/cover-letter-turns";
    private static final String FILL_PATH = "/internal/cover-letter-fill";

    // AI 가 다음 말은 20초, 다시 쓰기는 60초 안에 답하므로 그 답을 놓치지 않게 조금 더 기다린다
    private static final Duration TURN_READ_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration FILL_READ_TIMEOUT = Duration.ofSeconds(70);

    private static final Set<String> ITEMS = Set.of("SITUATION", "ACTION", "RESULT", "LEARNING", "TRIGGER",
            "JOB_LINK", "REASON");
    private static final Set<String> STATUSES = Set.of("ASKING", "FILLED", "DONE", "OFF_TOPIC");
    private static final String USER_REPLY = "USER_REPLY";
    private static final String REVERTED = "REVERTED";
    // 정리한 답은 다음 초안·첨삭 요청에 그대로 실려, AI 가 답 하나를 받는 상한을 넘으면 그 요청이 실패한다
    private static final int ANSWER_MAX_LENGTH = 1_000;

    private final AiHttpClient ai;

    @Override
    public CoverLetterTurnReply next(CoverLetterTurnRequest request) {

        JsonNode data;
        try {
            data = ai.post(TURN_PATH, request, TURN_READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw unavailable("다음 말", failure);
        }
        try {
            return reply(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable("다음 말", AiClientException.invalidResponse());
        }

    }

    @Override
    public CoverLetterFill fill(CoverLetterFillRequest request) {

        JsonNode data;
        try {
            data = ai.post(FILL_PATH, request, FILL_READ_TIMEOUT);
        } catch (AiClientException failure) {
            throw unavailable("다시 쓰기", failure);
        }
        try {
            return filled(data, request.target().questionId());
        } catch (RuntimeException malformed) {
            throw unavailable("다시 쓰기", AiClientException.invalidResponse());
        }

    }

    // 말은 대화가 이어지는 문항이면 다른 문항을 가리켜도 되지만, 답은 사용자가 방금 답한 문항에만 들어간다
    private static CoverLetterTurnReply reply(JsonNode data, CoverLetterTurnRequest request) {

        Set<Integer> questionIds = new HashSet<>();
        questionIds.add(request.question().questionId());
        request.others().stream().map(CoverLetterPolishRequest.Other::questionId).forEach(questionIds::add);

        JsonNode node = data.path("message");
        int questionId = AiCoverLetterDraftsAdapter.positiveInt(node, "question_id");
        if (!questionIds.contains(questionId)) {
            throw AiClientException.invalidResponse();
        }
        JsonNode item = node.path("item");
        CoverLetterTurnReply.Message message = new CoverLetterTurnReply.Message(
                AiCoverLetterDraftsAdapter.text(node, "text"), questionId,
                item.isNull() || item.isMissingNode() ? null : AiCoverLetterDraftsAdapter.oneOf(node, "item", ITEMS));

        CoverLetterTurnReply.Answer answer = answer(data.path("answer"));
        String status = AiCoverLetterDraftsAdapter.oneOf(data, "status", STATUSES);
        boolean replied = USER_REPLY.equals(request.trigger());
        if ((answer != null && !replied) || ("FILLED".equals(status) && answer == null)) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterTurnReply(message, answer, status);

    }

    private static CoverLetterTurnReply.Answer answer(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = AiCoverLetterDraftsAdapter.text(node, "text");
        if (text.length() > ANSWER_MAX_LENGTH || !node.path("sufficient").isBoolean()) {
            throw AiClientException.invalidResponse();
        }

        return new CoverLetterTurnReply.Answer(AiCoverLetterDraftsAdapter.oneOf(node, "item", ITEMS), text,
                node.path("sufficient").booleanValue());

    }

    // 과정은 저장하지 않지만, 모르는 값이 오면 AI 와 계약이 어긋난 것이라 다시 쓴 글도 믿지 않는다
    // AI 는 다시 쓴 글이 새 문제를 남기거나 다른 문장을 많이 바꾸면 앞 글로 되돌리고 과정에 REVERTED 를 남긴다
    private static CoverLetterFill filled(JsonNode data, int questionId) {

        CoverLetterDrafts.Draft draft = AiCoverLetterDraftsAdapter.draft(data.path("draft"));
        if (draft.questionId() != questionId) {
            throw AiClientException.invalidResponse();
        }
        boolean reverted = false;
        for (JsonNode step : AiCoverLetterDraftsAdapter.array(data, "steps")) {
            reverted |= REVERTED.equals(AiCoverLetterDraftsAdapter.step(step, Set.of(questionId)).kind());
        }

        return new CoverLetterFill(draft, reverted);

    }

    // AI 는 LLM 이 실패하면 503 을 돌려주므로, 그 실패는 다시 해 볼 만하다
    private static AgentUnavailableException unavailable(String what, AiClientException failure) {

        boolean retryable = failure.temporary();
        return new AgentUnavailableException("AI 자소서 대화 " + what + " 실패 (" + failure.errorCode() + ")",
                retryable, !retryable, failure);

    }
}
