package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ExclusionReason;
import com.gitory.backend.ingest.domain.GithubState;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Spring의 내부 저장소 ID를 수집 계약으로 변환하고 기존 저장 DTO로 투영한다. */
@Component
@RequiredArgsConstructor
public class AiRepositoryActivityAdapter implements RepositoryActivityPort {

    private final CollectionTargetLookup targets;
    private final GithubCollectionAccessPort access;

    @Override
    public CollectedActivity collect(IngestRequest request) {
        if (request == null || request.userRepositoryId() == null || request.userRepositoryId() <= 0) {
            throw new IllegalArgumentException("수집할 연결 저장소 ID가 필요하다");
        }
        if (request.since() != null) {
            throw new AiClientException("INCREMENTAL_COLLECTION_UNSUPPORTED", false, null);
        }
        List<String> branches = normalizedBranches(request.branches());
        CollectionTargetLookup.Target target = targets.find(request.userRepositoryId(), branches);
        JsonNode result = access.collect(target.userId(), target.collection());
        try {
            return activity(result, request.userRepositoryId(), target.collection().repository().githubRepoId());
        } catch (AiClientException safe) {
            throw safe;
        } catch (RuntimeException malformed) {
            throw AiClientException.invalidResponse();
        }
    }

    private static CollectedActivity activity(JsonNode result, long userRepositoryId, long githubRepoId) {
        if (result == null || !result.isObject() || integer(result, "user_repository_id") != userRepositoryId) {
            throw AiClientException.invalidResponse();
        }
        boolean partial = bool(result, "partial");
        String reason = optionalText(result, "partial_reason");
        if (partial != (reason != null)) {
            throw AiClientException.invalidResponse();
        }
        PartialReason partialReason = reason == null ? null : PartialReason.valueOf(reason);
        // AI 계약에는 TIMEOUT 부분 응답이 없다. 전송 timeout은 재시도 가능한 호출 오류다.
        if (partialReason == PartialReason.TIMEOUT) {
            throw AiClientException.invalidResponse();
        }
        List<CollectedCommit> commits = new ArrayList<>();
        for (JsonNode commit : array(result, "commits")) {
            if (integer(commit, "repository_id") != githubRepoId) {
                throw AiClientException.invalidResponse();
            }
            String sha = text(commit, "sha");
            if (!sha.matches("[0-9a-fA-F]{7,40}")) {
                throw AiClientException.invalidResponse();
            }
            String exclusion = optionalText(commit, "exclusion_reason");
            if (bool(commit, "is_excluded") != (exclusion != null)) {
                throw AiClientException.invalidResponse();
            }
            int parents = nonNegativeInt(commit, "parent_count");
            if (parents > Short.MAX_VALUE) {
                throw AiClientException.invalidResponse();
            }
            commits.add(new CollectedCommit(sha, optionalText(commit, "author_login"),
                    optionalText(commit, "author_name"), text(commit, "message"),
                    Instant.parse(text(commit, "authored_at")), nonNegativeInt(commit, "additions"),
                    nonNegativeInt(commit, "deletions"), nonNegativeInt(commit, "changed_files"),
                    (short) parents, exclusion == null ? null : ExclusionReason.valueOf(exclusion)));
        }
        List<CollectedPullRequest> prs = new ArrayList<>();
        for (JsonNode pr : array(result, "pull_requests")) {
            prs.add(pullRequest(pr));
        }
        List<CollectedIssue> issues = new ArrayList<>();
        for (JsonNode issue : array(result, "issues")) {
            issues.add(issue(issue));
        }
        return new CollectedActivity(List.copyOf(commits), List.copyOf(prs), List.copyOf(issues), partialReason);
    }

    private static List<String> normalizedBranches(List<String> branches) {
        if (branches == null) { return List.of(); }
        return branches.stream().map(branch -> {
            if (branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("빈 브랜치는 수집할 수 없다");
            }
            return branch.trim();
        }).distinct().toList();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) { throw AiClientException.invalidResponse(); }
        return value;
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw AiClientException.invalidResponse();
        }
        return value.longValue();
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        long value = integer(node, field);
        if (value < 0 || value > Integer.MAX_VALUE) { throw AiClientException.invalidResponse(); }
        return (int) value;
    }

    private static int positiveInt(JsonNode node, String field) {
        int value = nonNegativeInt(node, field);
        if (value == 0) { throw AiClientException.invalidResponse(); }
        return value;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) { throw AiClientException.invalidResponse(); }
        return value.booleanValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) { throw AiClientException.invalidResponse(); }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) { return null; }
        if (!value.isString()) { throw AiClientException.invalidResponse(); }
        return value.asString();
    }

    private static CollectedPullRequest pullRequest(JsonNode pr) {

        return new CollectedPullRequest(positiveInt(pr, "number"), withoutNul(text(pr, "title")),
                withoutNul(optionalText(pr, "body_excerpt")), GithubState.valueOf(text(pr, "state")),
                optionalText(pr, "author_login"), text(pr, "base_branch"), text(pr, "head_branch"),
                Instant.parse(text(pr, "created_at")), optionalInstant(pr, "merged_at"), shas(pr, "commit_shas"),
                issueNumbers(pr, "linked_issue_numbers"));

    }

    private static CollectedIssue issue(JsonNode issue) {

        return new CollectedIssue(positiveInt(issue, "issue_number"), withoutNul(text(issue, "title")),
                withoutNul(optionalText(issue, "body_excerpt")), GithubState.valueOf(text(issue, "state")),
                optionalText(issue, "author_login"), texts(issue, "labels"), Instant.parse(text(issue, "created_at")),
                optionalInstant(issue, "closed_at"));

    }

    private static Instant optionalInstant(JsonNode node, String field) {

        String value = optionalText(node, field);
        return value == null ? null : Instant.parse(value);

    }

    private static List<String> shas(JsonNode node, String field) {

        List<String> shas = new ArrayList<>();
        for (JsonNode sha : array(node, field)) {
            if (!sha.isString() || !sha.asString().matches("[0-9a-fA-F]{7,40}")) {
                throw AiClientException.invalidResponse();
            }
            shas.add(sha.asString());
        }

        return List.copyOf(shas);

    }

    private static List<String> texts(JsonNode node, String field) {

        List<String> texts = new ArrayList<>();
        for (JsonNode text : array(node, field)) {
            if (!text.isString()) {
                throw AiClientException.invalidResponse();
            }
            texts.add(withoutNul(text.asString()));
        }

        return List.copyOf(texts);

    }

    /** PostgreSQL 문자 칸은 U+0000 을 저장하지 못해, 사람이 쓴 PR·이슈 글에 섞여 오면 저장 전체가 실패하지 않게 지운다 */
    private static String withoutNul(String value) {

        return value == null ? null : value.replace("\0", "");

    }

    /** PR 제목·본문에 적힌 #n 에서 뽑은 번호라 INT 를 넘는 값이 올 수 있어, 그런 번호만 버리고 응답 전체는 살린다 */
    private static List<Integer> issueNumbers(JsonNode node, String field) {

        List<Integer> numbers = new ArrayList<>();
        for (JsonNode number : array(node, field)) {
            if (!number.isIntegralNumber()) {
                throw AiClientException.invalidResponse();
            }
            if (number.canConvertToInt() && number.intValue() > 0) {
                numbers.add(number.intValue());
            }
        }

        return List.copyOf(numbers);

    }
}
