package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.ExtractedCriterion;
import com.gitory.backend.agent.port.ExtractedExperience;
import com.gitory.backend.agent.port.JobPostingExtractionPort;
import com.gitory.backend.agent.port.JobPostingExtractionRequest;
import com.gitory.backend.agent.port.JobPostingExtractionResult;
import com.gitory.backend.agent.port.PostingContentStatus;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.agent.port.PostingEducationLevel;
import com.gitory.backend.agent.port.PostingExperienceLevel;
import com.gitory.backend.agent.port.PostingExtractionUnavailableException;
import com.gitory.backend.agent.port.PostingGeneralType;
import com.gitory.backend.agent.port.PostingJobCategory;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI 공고 조건 뽑기를 부르고, 응답이 DB 에 그대로 저장해도 되는 모양인지 확인한 뒤 돌려준다
 * 원문 근거를 확인할 수 없다는 거절(422)은 실패가 아니라 결과 하나로 돌려주고, 나머지 실패는 코드와 함께 던진다
 */
@Component
@RequiredArgsConstructor
public class AiJobPostingExtractionAdapter implements JobPostingExtractionPort {

    private static final String PATH = "/internal/job-postings/extract";
    private static final String EVIDENCE_MISMATCH = "EVIDENCE_MISMATCH";
    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_CRITERION_TEXT = 1000;
    private static final int MAX_CRITERION_QUOTE = 4000;
    private static final int MAX_EXPERIENCE_QUOTE = 1000;
    private static final int MAX_EXPERIENCE_YEARS = 60;

    private final AiHttpClient ai;
    private final AiClientProperties properties;

    @Override
    public JobPostingExtractionResult extract(JobPostingExtractionRequest request) {

        JsonNode data;
        try {
            data = ai.post(PATH, request, properties.postingExtractReadTimeout());
        } catch (AiClientException failure) {
            if (EVIDENCE_MISMATCH.equals(failure.errorCode())) {
                return JobPostingExtractionResult.rejected();
            }
            throw unavailable(failure);
        }
        try {
            return result(data, request);
        } catch (RuntimeException malformed) {
            // 응답 원문이 든 예외는 원인으로 넘기지 않는다
            throw unavailable(AiClientException.invalidResponse());
        }

    }

    private static JobPostingExtractionResult result(JsonNode data, JobPostingExtractionRequest request) {

        if (!request.source().equals(string(data, "source"))
                || !request.externalPostingId().equals(string(data, "external_posting_id"))) {
            throw AiClientException.invalidResponse();
        }
        String contentHash = string(data, "content_hash");
        if (!CONTENT_HASH.matcher(contentHash).matches()) {
            throw AiClientException.invalidResponse();
        }
        PostingContentStatus contentStatus = PostingContentStatus.valueOf(string(data, "content_status"));
        List<ExtractedCriterion> criteria = criteria(array(data, "criteria"));
        return switch (string(data, "outcome")) {
            case "UNCHANGED" -> unchanged(request, contentHash, contentStatus, criteria);
            case "UPDATED" -> updated(data, contentHash, contentStatus, criteria);
            default -> throw AiClientException.invalidResponse();
        };

    }

    // 이전 해시를 보내지 않았거나 해시가 다른데 UNCHANGED 가 오면 저장된 공고가 조용히 PENDING 으로 남는다
    private static JobPostingExtractionResult unchanged(JobPostingExtractionRequest request, String contentHash,
                                                        PostingContentStatus contentStatus,
                                                        List<ExtractedCriterion> criteria) {

        if (!criteria.isEmpty() || !contentHash.equals(request.previousContentHash())) {
            throw AiClientException.invalidResponse();
        }
        return JobPostingExtractionResult.unchanged(contentHash, contentStatus);

    }

    // 조건이 있으면 READY 이고 없으면 METADATA_ONLY 라서 둘이 어긋난 응답은 저장하지 않는다
    private static JobPostingExtractionResult updated(JsonNode data, String contentHash,
                                                      PostingContentStatus contentStatus,
                                                      List<ExtractedCriterion> criteria) {

        if ((contentStatus == PostingContentStatus.READY) == criteria.isEmpty()) {
            throw AiClientException.invalidResponse();
        }
        return JobPostingExtractionResult.updated(contentHash, contentStatus, criteria,
                category(string(data, "job_category")), experience(data.path("experience")),
                names(data.path("team_techs")), optionalVersion(data.path("extraction_version")));

    }

    private static List<ExtractedCriterion> criteria(JsonNode nodes) {

        List<ExtractedCriterion> criteria = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                throw AiClientException.invalidResponse();
            }
            PostingGeneralType generalType = generalType(node.path("general_type"));
            boolean education = generalType == PostingGeneralType.EDUCATION;
            criteria.add(new ExtractedCriterion(PostingCriterionType.valueOf(string(node, "criterion_type")),
                    text(node, "text", MAX_CRITERION_TEXT), text(node, "source_quote", MAX_CRITERION_QUOTE),
                    generalType, names(node.path("techs")),
                    education ? educationLevel(node.path("education_level")) : null,
                    education && optionalFlag(node.path("education_exempt"))));
        }
        return List.copyOf(criteria);

    }

    // AI 가 종류를 하나 늘려도 공고 전체가 이상한 응답이 되지 않게 모르는 종류는 기타 조건으로 둔다
    private static PostingGeneralType generalType(JsonNode value) {

        String name = optionalString(value);
        if (name == null) {
            return null;
        }
        try {
            return PostingGeneralType.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return PostingGeneralType.OTHER;
        }

    }

    // 모르는 학력 수준을 다른 단계로 읽으면 맞는 사람을 거를 수 있어 수준 없음으로 둔다
    private static PostingEducationLevel educationLevel(JsonNode value) {

        String name = optionalString(value);
        if (name == null) {
            return null;
        }
        try {
            return PostingEducationLevel.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }

    }

    // 기술 이름이 65개 목록에 있는지는 목록을 아는 매칭 쪽이 거른다
    private static List<String> names(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw AiClientException.invalidResponse();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode name : value) {
            if (!name.isString()) {
                throw AiClientException.invalidResponse();
            }
            names.add(name.asString());
        }
        return List.copyOf(names);

    }

    // AI 가 분류를 하나 늘려도 공고 전체가 이상한 응답이 되지 않게 모르는 값은 기타로 둔다
    private static PostingJobCategory category(String value) {

        try {
            return PostingJobCategory.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return PostingJobCategory.ETC;
        }

    }

    // 표 CHECK 와 같은 규칙이라 어긋난 값은 저장 단계에서 DB 오류가 되기 전에 막는다
    private static ExtractedExperience experience(JsonNode node) {

        if (!node.isObject()) {
            throw AiClientException.invalidResponse();
        }
        PostingExperienceLevel level = PostingExperienceLevel.valueOf(string(node, "level"));
        Integer minYears = optionalYears(node.path("min_years"));
        JsonNode quoteNode = node.path("source_quote");
        String quote = quoteNode.isNull() || quoteNode.isMissingNode() ? null : text(node, "source_quote", MAX_EXPERIENCE_QUOTE);
        boolean valid = switch (level) {
            case UNSPECIFIED -> minYears == null && quote == null;
            case ENTRY -> minYears == null && quote != null;
            case EXPERIENCED -> minYears != null && minYears >= 1 && minYears <= MAX_EXPERIENCE_YEARS && quote != null;
        };
        if (!valid) {
            throw AiClientException.invalidResponse();
        }
        return new ExtractedExperience(level, minYears, quote);

    }

    private static Integer optionalYears(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static Integer optionalVersion(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isInt() || value.intValue() < 1) {
            throw AiClientException.invalidResponse();
        }
        return value.intValue();

    }

    private static boolean optionalFlag(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw AiClientException.invalidResponse();
        }
        return value.booleanValue();

    }

    private static String optionalString(JsonNode value) {

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw AiClientException.invalidResponse();
        }
        return value.asString();

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

    private static String text(JsonNode node, String field, int maxLength) {

        String value = string(node, field);
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw AiClientException.invalidResponse();
        }
        return value;

    }

    private static PostingExtractionUnavailableException unavailable(AiClientException failure) {

        boolean retryable = retryable(failure);
        return new PostingExtractionUnavailableException("AI 공고 조건 뽑기 실패 (" + failure.errorCode() + ")",
                retryable, !retryable, failure.errorCode(), failure);

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
