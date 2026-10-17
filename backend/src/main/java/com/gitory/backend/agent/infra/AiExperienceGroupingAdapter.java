package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.ExperienceCandidate;
import com.gitory.backend.agent.port.ExperienceGroupingPort;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.agent.port.ExperienceGroupingResponse;
import com.gitory.backend.agent.port.ExperienceSourceType;
import com.gitory.backend.agent.port.GroupingCommit;
import com.gitory.backend.agent.port.GroupingVerdict;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 경험 그룹화 API 를 부르고, 응답이 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 후보 커밋은 보낸 커밋에만 이어 저장하고 한 커밋은 한 후보에만 두므로, 이를 어긴 응답도 이상 응답으로 끝낸다
 */
@Component
@RequiredArgsConstructor
public class AiExperienceGroupingAdapter implements ExperienceGroupingPort {

    private static final String PATH = "/internal/analysis/groups";

    // AI 계약의 길이 상한이며 DB candidate.title·reason_text 길이와 같다
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int REASON_MAX_LENGTH = 300;

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public ExperienceGroupingResponse group(ExperienceGroupingRequest request) {

        Set<String> sentShas = new HashSet<>();
        for (GroupingCommit commit : request.commits()) {
            sentShas.add(commit.sha());
        }
        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.groupsReadTimeout());
        } catch (AiClientException failure) {
            throw unavailable(failure);
        }
        try {
            return response(data, sentShas);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static ExperienceGroupingResponse response(JsonNode data, Set<String> sentShas) {

        GroupingVerdict verdict = GroupingVerdict.valueOf(string(data, "verdict"));
        Set<String> groupedShas = new HashSet<>();
        List<ExperienceCandidate> candidates = new ArrayList<>();
        for (JsonNode candidate : array(data, "candidates")) {
            candidates.add(candidate(candidate, sentShas, groupedShas));
        }
        return new ExperienceGroupingResponse(verdict, List.copyOf(candidates), strings(data, "excluded_commit_shas"));

    }

    private static ExperienceCandidate candidate(JsonNode node, Set<String> sentShas, Set<String> groupedShas) {

        ExperienceSourceType sourceType = ExperienceSourceType.valueOf(string(node, "source_type"));
        Integer pullRequestNumber = optionalPositiveInt(node, "pull_request_number");
        List<Integer> pullRequestNumbers = positiveInts(node, "pull_request_numbers");
        boolean pr = sourceType == ExperienceSourceType.PR;
        // DB 는 PR 후보에만 대표 PR 번호를 허용하고, 다시 분석할 때는 PR 목록으로 손댄 PR 을 지킨다
        // 그래서 종류·대표 PR·PR 목록이 서로 어긋난 후보는 받지 않는다
        if (pr != (pullRequestNumber != null) || pr == pullRequestNumbers.isEmpty()
                || (pr && !pullRequestNumbers.contains(pullRequestNumber))) {
            throw AiClientException.invalidResponse();
        }
        List<String> commitShas = strings(node, "commit_shas");
        if (commitShas.isEmpty()) {
            throw AiClientException.invalidResponse();
        }
        for (String sha : commitShas) {
            if (!sentShas.contains(sha) || !groupedShas.add(sha)) {
                throw AiClientException.invalidResponse();
            }
        }
        return new ExperienceCandidate(string(node, "group_key"), sourceType, optionalString(node, "source_ref"),
                pullRequestNumber, pullRequestNumbers, positiveInts(node, "issue_numbers"),
                limitedText(node, "title", TITLE_MAX_LENGTH), limitedText(node, "reason", REASON_MAX_LENGTH),
                score(node), bool(node, "low_card_worth"), commitShas);

    }

    private static JsonNode array(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static String string(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

    }

    private static String optionalString(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return string(node, field);

    }

    private static String limitedText(JsonNode node, String field, int maxLength) {

        String value = string(node, field);
        // AI 와 DB 는 글자 수를 코드 포인트로 세서, UTF-16 길이로 재면 이모지가 든 글을 잘못 거절한다
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static List<String> strings(JsonNode node, String field) {

        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isString()) {
                throw AiClientException.invalidResponse();
            }
            values.add(value.asString());
        }
        return List.copyOf(values);

    }

    private static Integer optionalPositiveInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return positiveInt(value);

    }

    private static List<Integer> positiveInts(JsonNode node, String field) {

        List<Integer> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            values.add(positiveInt(value));
        }
        return List.copyOf(values);

    }

    private static int positiveInt(JsonNode value) {

        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static double score(JsonNode node) {

        JsonNode value = node.path("score");
        if (!value.isNumber() || value.doubleValue() < 0.0 || value.doubleValue() > 1.0) {
            throw AiClientException.invalidResponse();
        }
        return value.doubleValue();

    }

    private static boolean bool(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw AiClientException.invalidResponse();
        }
        return value.booleanValue();

    }

    private static AgentUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new AgentUnavailableException("AI 경험 그룹화 실패 (" + failure.errorCode() + ")", retryable,
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
