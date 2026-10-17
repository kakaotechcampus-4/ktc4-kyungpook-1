package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.consent.port.DiffEvidenceTarget;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ExclusionReason;
import com.gitory.backend.ingest.domain.FileChangeStatus;
import com.gitory.backend.ingest.domain.GithubState;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.domain.ReviewState;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedFile;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.CollectedReview;
import com.gitory.backend.ingest.port.CollectedReviewComment;
import com.gitory.backend.ingest.port.DiffEvidenceRequest;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        JsonNode result = access.collect(target.userId(), target.collection(), request.knownCommitShas());
        try {
            return activity(result, request.userRepositoryId(), target.collection().repository().githubRepoId());
        } catch (AiClientException safe) {
            throw safe;
        } catch (RuntimeException malformed) {
            throw AiClientException.invalidResponse();
        }
    }

    @Override
    public DiffEvidenceResult diffEvidence(DiffEvidenceRequest request) {

        CollectionTargetLookup.Target target = targets.find(request.userRepositoryId(), List.of());
        return access.diffEvidence(target.userId(), new DiffEvidenceTarget(request.userRepositoryId(),
                target.collection().repository(), request.commitShas(), request.githubPrNumber(), request.title()));

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
                    (short) parents, exclusionReason(exclusion), files(commit),
                    issueNumbers(commit, "issue_numbers")));
        }
        List<CollectedPullRequest> prs = new ArrayList<>();
        for (JsonNode pr : array(result, "pull_requests")) {
            prs.add(pullRequest(pr));
        }
        List<CollectedIssue> issues = new ArrayList<>();
        for (JsonNode issue : array(result, "issues")) {
            issues.add(issue(issue));
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

    private static Integer optionalPositiveInt(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        return positiveInt(node, field);

    }

    /** GitHub 의 리뷰·댓글 id 는 INT 를 넘으므로 long 으로 읽는다 */
    private static long positiveLong(JsonNode node, String field) {

        long value = integer(node, field);
        if (value <= 0) {
            throw AiClientException.invalidResponse();
        }

        return value;

    }

    private static Long optionalPositiveLong(JsonNode node, String field) {

        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        return positiveLong(node, field);

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

    /** 커밋 행은 사용자끼리 함께 쓰는데 AI 의 NOT_OWN 은 요청한 사람 기준이라, 제외하지 않은 커밋으로 읽는다 */
    private static ExclusionReason exclusionReason(String reason) {

        if (reason == null || reason.equals("NOT_OWN")) {
            return null;
        }

        return ExclusionReason.valueOf(reason);

    }

    private static List<CollectedFile> files(JsonNode commit) {

        List<CollectedFile> files = new ArrayList<>();
        for (JsonNode file : array(commit, "files")) {
            files.add(new CollectedFile(text(file, "path"), FileChangeStatus.valueOf(text(file, "status")),
                    nonNegativeInt(file, "additions"), nonNegativeInt(file, "deletions")));
        }

        return List.copyOf(files);

    }

    private static CollectedPullRequest pullRequest(JsonNode pr) {

        return new CollectedPullRequest(positiveInt(pr, "number"), withoutNul(text(pr, "title")),
                withoutNul(optionalText(pr, "body_excerpt")), GithubState.valueOf(text(pr, "state")),
                optionalText(pr, "author_login"), text(pr, "base_branch"), text(pr, "head_branch"),
                Instant.parse(text(pr, "created_at")), optionalInstant(pr, "merged_at"), shas(pr, "commit_shas"),
                issueNumbers(pr, "linked_issue_numbers"), reviews(pr));

    }

    /**
     * 제출하지 않은 PENDING 리뷰는 작성자에게만 보이는 초안이라, 사용자끼리 함께 쓰는 표에 남지 않게 그 리뷰와 그 리뷰 id 를 가진 줄 댓글을 넘기지 않는다
     * 다른 리뷰와 같은 검사를 거치도록 다 읽은 뒤에 빼고, 제출되면 다음 수집이 같은 GitHub id 로 가져온다
     */
    private static List<CollectedReview> reviews(JsonNode pr) {

        Set<Long> pendingIds = pendingReviewIds(pr);
        List<CollectedReview> reviews = new ArrayList<>();
        for (JsonNode review : array(pr, "reviews")) {
            CollectedReview collected = new CollectedReview(positiveLong(review, "review_id"),
                    reviewState(text(review, "state")), optionalText(review, "author_login"),
                    withoutNul(optionalText(review, "body_excerpt")), optionalInstant(review, "submitted_at"),
                    reviewComments(review, pendingIds));
            if (!pendingIds.contains(collected.reviewId())) {
                reviews.add(collected);
            }
        }

        return List.copyOf(reviews);

    }

    private static Set<Long> pendingReviewIds(JsonNode pr) {

        Set<Long> ids = new HashSet<>();
        for (JsonNode review : array(pr, "reviews")) {
            if (text(review, "state").equals("PENDING")) {
                ids.add(positiveLong(review, "review_id"));
            }
        }

        return ids;

    }

    /**
     * AI 는 GitHub 리뷰 상태를 대문자로만 바꿔 그대로 보내므로 모르는 상태가 올 수 있다
     * 그런 리뷰를 저장하면 CHECK 에 걸려 같은 수집 전체가 되돌아가므로 null 로 넘겨 그 리뷰 행만 건너뛰게 한다
     */
    private static ReviewState reviewState(String state) {

        return Arrays.stream(ReviewState.values())
                .filter(known -> known.name().equals(state))
                .findFirst()
                .orElse(null);

    }

    private static List<CollectedReviewComment> reviewComments(JsonNode review, Set<Long> pendingIds) {

        List<CollectedReviewComment> comments = new ArrayList<>();
        for (JsonNode comment : array(review, "comments")) {
            CollectedReviewComment collected = new CollectedReviewComment(positiveLong(comment, "comment_id"),
                    optionalPositiveLong(comment, "review_id"), optionalText(comment, "author_login"),
                    withoutNul(text(comment, "body_excerpt")), optionalText(comment, "path"),
                    optionalPositiveInt(comment, "line"), Instant.parse(text(comment, "created_at")));
            if (!pendingIds.contains(collected.reviewId())) {
                comments.add(collected);
            }
        }

        return List.copyOf(comments);

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

    /** PostgreSQL 문자 칸은 U+0000 을 저장하지 못해, 사람이 쓴 PR·이슈·리뷰 글에 섞여 오면 저장 전체가 실패하지 않게 지운다 */
    private static String withoutNul(String value) {

        return value == null ? null : value.replace("\0", "");

    }

    /** PR 제목·본문과 커밋 메시지에 적힌 #n 에서 뽑은 번호라 INT 를 넘는 값이 올 수 있어, 그런 번호만 버리고 응답 전체는 살린다 */
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
