package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ExclusionReason;
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
        List<String> branches = normalizedBranches(request.branches());
        CollectionTargetLookup.Target target = targets.find(request.userRepositoryId(), branches);
        JsonNode result = access.collect(target.userId(), target.collection(), request.since());
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
        String headSha = optionalText(result, "head_sha");
        if (headSha != null && !headSha.matches("[0-9a-fA-F]{7,40}")) {
            throw AiClientException.invalidResponse();
        }
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
            prs.add(new CollectedPullRequest(positiveInt(pr, "number")));
        }
        List<CollectedIssue> issues = new ArrayList<>();
        for (JsonNode issue : array(result, "issues")) {
            issues.add(new CollectedIssue(positiveInt(issue, "issue_number")));
        }
        return new CollectedActivity(List.copyOf(commits), List.copyOf(prs), List.copyOf(issues), headSha, partialReason);
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
}
