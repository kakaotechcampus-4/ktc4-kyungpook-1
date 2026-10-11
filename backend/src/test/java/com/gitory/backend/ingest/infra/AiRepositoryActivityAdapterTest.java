package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.infra.GithubCollectionAccess;
import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.consent.port.GithubCollectionRepo;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import com.gitory.backend.ingest.domain.ExclusionReason;
import com.gitory.backend.ingest.domain.FileChangeStatus;
import com.gitory.backend.ingest.domain.GithubState;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.domain.ReviewState;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedFile;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.CollectedReview;
import com.gitory.backend.ingest.port.CollectedReviewComment;
import com.gitory.backend.ingest.port.IngestRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실제 HTTP를 거쳐 공유 계약의 Spring→AI 및 AI→Spring 경계를 검사한다. */
class AiRepositoryActivityAdapterTest {

    private static final String TOKEN = "contract-only-token";
    private static final long USER_ID = 7;
    private static final long USER_REPOSITORY_ID = 123;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final CollectionTargetLookup targets = mock(CollectionTargetLookup.class);
    private final TokenCipher cipher = new TokenCipher("contract-test-key", "0123456789abcdef");
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private AiRepositoryActivityAdapter adapter;
    private AiHttpClient http;
    private ObjectNode data;
    private String responseBody;
    private int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile boolean responseHeadersFirst;
    private volatile String requestBody;
    private volatile String requestToken;
    private volatile String requestMethod;
    private volatile String requestPath;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode fixture = mapper.readTree(Path.of("../contracts/ingest/repository-activity.json"));
        data = (ObjectNode) fixture.path("ai_to_spring").path("data").deepCopy();
        responseBody = success(data);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (responseHeadersFirst) {
                exchange.sendResponseHeaders(responseStatus, body.length);
            }
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            if (!responseHeadersFirst) {
                exchange.sendResponseHeaders(responseStatus, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        http = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2)), mapper);
        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));
        activeConnection(null);
        when(targets.find(eq(USER_REPOSITORY_ID), anyList())).thenAnswer(invocation ->
                new CollectionTargetLookup.Target(USER_ID,
                        new GithubCollectionTarget(USER_REPOSITORY_ID,
                                new GithubCollectionRepo(456789L, "grow22", "gitory", "develop"),
                                invocation.getArgument(1))));
        adapter = new AiRepositoryActivityAdapter(targets,
                new GithubCollectionAccess(users, connections, cipher, http, null, null));
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    @DisplayName("DB 저장소 정보와 서버 사용자로 요청을 만들고, 토큰은 헤더에만 전달한다")
    void collectsUsingSharedContractOverHttp() {
        var activity = adapter.collect(request(List.of(" feature/session-index ", "feature/session-index")));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo("/internal/collect");
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat(requestBody).doesNotContain(TOKEN).doesNotContain("token");
        JsonNode sent = mapper.readTree(requestBody);
        assertThat(sent.path("user_repository_id").longValue()).isEqualTo(USER_REPOSITORY_ID);
        assertThat(sent.path("repository").path("github_repo_id").longValue()).isEqualTo(456789L);
        assertThat(sent.path("repository").path("default_branch").asString()).isEqualTo("develop");
        assertThat(sent.path("actor").path("github_login").asString()).isEqualTo("grow22");
        assertThat(sent.path("actor").path("known_emails").isEmpty()).isTrue();
        assertThat(sent.path("branches").size()).isEqualTo(1);
        assertThat(sent.path("branches").path(0).asString()).isEqualTo("feature/session-index");
        assertThat(sent.has("since")).isFalse();
        assertThat(activity.headSha()).isEqualTo("abc1234567890");
        assertThat(activity.commits()).hasSize(1);
        assertThat(activity.commits().getFirst().sha()).isEqualTo("abc1234567890");
        assertThat(activity.commits().getFirst().authoredAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
        assertThat(activity.pullRequests().getFirst().number()).isEqualTo(17);
        assertThat(activity.issues().getFirst().issueNumber()).isEqualTo(42);
        assertThat(activity.partialReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB_RATE_LIMITED", "CAP_EXCEEDED"})
    @DisplayName("부분 수집도 활동과 정확한 사유를 보존한다")
    void keepsPartialCollection(String reason) {
        data.put("partial", true).put("partial_reason", reason);
        ((ObjectNode) data.path("commits").path(0)).put("is_excluded", true).put("exclusion_reason", "BOT");
        responseBody = success(data);
        var activity = adapter.collect(request(List.of()));
        assertThat(activity.partialReason()).isEqualTo(PartialReason.valueOf(reason));
        assertThat(activity.commits().getFirst().exclusionReason()).isEqualTo(ExclusionReason.BOT);
    }

    @Test
    @DisplayName("user_repository_id 불일치 응답은 저장으로 넘기지 않는다")
    void rejectsWrongConnectionIdentity() {
        data.put("user_repository_id", 124);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("다른 GitHub 저장소에서 온 커밋은 저장으로 넘기지 않는다")
    void rejectsWrongGithubRepositoryIdentity() {
        ((ObjectNode) data.path("commits").path(0)).put("repository_id", 999);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("부분 여부와 사유가 어긋난 응답은 거절한다")
    void rejectsInconsistentPartialFlags() {
        data.put("partial", true);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("음수 숫자·범위 넘는 parent_count 응답은 거절한다")
    void rejectsOverflowingParentCount() {
        ((ObjectNode) data.path("commits").path(0)).put("parent_count", 40000);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @ParameterizedTest
    @CsvSource({"true,", "false, BOT", "false, NOT_OWN"})
    @DisplayName("커밋의 제외 여부와 제외 사유가 어긋나면 사유가 NOT_OWN 이어도 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMismatchedExclusion(boolean excluded, String reason) {

        firstCommit().put("is_excluded", excluded).put("exclusion_reason", reason);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("AI 가 NOT_OWN 으로 제외한 커밋은 제외하지 않은 커밋으로 읽는다")
    void readsNotOwnCommitAsNotExcluded() {

        firstCommit().put("is_excluded", true).put("exclusion_reason", "NOT_OWN");
        responseBody = success(data);

        assertThat(collectFirstCommit().exclusionReason()).isNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {"BOT", "MERGE_COMMIT", "LOCKFILE_ONLY"})
    @DisplayName("NOT_OWN 이 아닌 제외 사유 세 가지는 그 사유 그대로 읽는다")
    void readsOtherExclusionReasonsAsSent(String reason) {

        firstCommit().put("is_excluded", true).put("exclusion_reason", reason);
        responseBody = success(data);

        assertThat(collectFirstCommit().exclusionReason()).isEqualTo(ExclusionReason.valueOf(reason));

    }

    @Test
    @DisplayName("AI 가 커밋마다 보낸 바뀐 파일과 이슈 번호를 받은 그대로 읽는다")
    void readsCommitFilesAndIssueNumbers() {

        CollectedCommit commit = collectFirstCommit();

        assertThat(commit.files()).containsExactly(
                new CollectedFile("backend/session.sql", FileChangeStatus.MODIFIED, 10, 3),
                new CollectedFile("docs/index.png", FileChangeStatus.ADDED, 0, 0));
        assertThat(commit.issueNumbers()).containsExactly(42);

    }

    @ParameterizedTest
    @ValueSource(strings = {"ADDED", "MODIFIED", "REMOVED", "RENAMED", "COPIED", "CHANGED", "UNCHANGED", "UNKNOWN"})
    @DisplayName("파일 상태 여덟 가지는 그 상태 그대로 읽는다")
    void readsEveryFileStatus(String status) {

        firstFile().put("status", status);
        responseBody = success(data);

        assertThat(collectFirstCommit().files().getFirst().status()).isEqualTo(FileChangeStatus.valueOf(status));

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "\"[]\"", "3"})
    @DisplayName("커밋의 files 가 없거나 배열이 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMissingOrNonArrayFiles(String json) {

        replace(firstCommit(), "files", json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @CsvSource({
            "path,", "path, null", "path, \"\"", "path, \"   \"", "path, 3",
            "status,", "status, null", "status, \"RESOLVED\"", "status, 3",
            "additions, -1", "additions, 2147483648", "additions, 1.5", "additions, \"10\"",
            "deletions, -1", "deletions, 2147483648", "deletions, 1.5", "deletions, \"3\""})
    @DisplayName("바뀐 파일의 경로가 비었거나 상태가 여덟 가지 밖이거나 줄 수가 음수·INT 초과·정수가 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsInvalidFile(String field, String json) {

        replace(firstFile(), field, json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("커밋의 이슈 번호 중 양의 int 에 들지 않는 번호는 그 번호만 버리고 나머지는 읽는다")
    void dropsCommitIssueNumbersOutsidePositiveInt() {

        firstCommit().putArray("issue_numbers")
                .add(42).add(2_147_483_648L).add(0).add(-3).add(new BigInteger("99999999999999999999")).add(7);
        responseBody = success(data);

        assertThat(collectFirstCommit().issueNumbers()).containsExactly(42, 7);

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "null")
    @DisplayName("커밋의 pull_request_number 는 읽지 않아 없거나 null 이어도 커밋을 그대로 읽는다")
    void ignoresCommitPullRequestNumber(String json) {

        replace(firstCommit(), "pull_request_number", json);
        responseBody = success(data);

        assertThat(collectFirstCommit().sha()).isEqualTo("abc1234567890");

    }

    @Test
    @DisplayName("AI 가 보낸 PR·이슈의 상세 값을 칸마다 받은 그대로 읽는다")
    void readsPullRequestAndIssueDetails() {

        var activity = adapter.collect(request(List.of()));

        CollectedPullRequest pr = activity.pullRequests().getFirst();
        assertThat(pr.number()).isEqualTo(17);
        assertThat(pr.title()).isEqualTo("세션 조회 성능 개선");
        assertThat(pr.bodyExcerpt()).isEqualTo("인덱스를 추가합니다. Resolves #42");
        assertThat(pr.state()).isEqualTo(GithubState.CLOSED);
        assertThat(pr.authorLogin()).isEqualTo("grow22");
        assertThat(pr.baseBranch()).isEqualTo("develop");
        assertThat(pr.headBranch()).isEqualTo("feature/session-index");
        assertThat(pr.openedAt()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
        assertThat(pr.mergedAt()).isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));
        assertThat(pr.commitShas()).containsExactly("abc1234567890");
        assertThat(pr.linkedIssueNumbers()).containsExactly(42);

        CollectedIssue issue = activity.issues().getFirst();
        assertThat(issue.issueNumber()).isEqualTo(42);
        assertThat(issue.title()).isEqualTo("세션 조회가 느립니다");
        assertThat(issue.bodyExcerpt()).isEqualTo("응답 시간이 오래 걸립니다.");
        assertThat(issue.state()).isEqualTo(GithubState.CLOSED);
        assertThat(issue.authorLogin()).isEqualTo("reporter");
        assertThat(issue.labels()).containsExactly("performance");
        assertThat(issue.openedAt()).isEqualTo(Instant.parse("2026-09-19T08:00:00Z"));
        assertThat(issue.closedAt()).isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));

    }

    @Test
    @DisplayName("열려 있는 PR·이슈의 본문·작성자·머지 시각·닫힌 시각이 null 이면 그 값을 비운 채 읽는다")
    void readsOpenPullRequestAndIssueWithoutOptionalValues() {

        firstPullRequest().put("state", "OPEN").putNull("body_excerpt").putNull("author_login").putNull("merged_at");
        firstPullRequest().putArray("commit_shas");
        firstPullRequest().putArray("linked_issue_numbers");
        firstIssue().put("state", "OPEN").putNull("body_excerpt").putNull("author_login").putNull("closed_at");
        firstIssue().putArray("labels");
        responseBody = success(data);

        var activity = adapter.collect(request(List.of()));

        CollectedPullRequest pr = activity.pullRequests().getFirst();
        assertThat(pr.state()).isEqualTo(GithubState.OPEN);
        assertThat(pr.bodyExcerpt()).isNull();
        assertThat(pr.authorLogin()).isNull();
        assertThat(pr.mergedAt()).isNull();
        assertThat(pr.commitShas()).isEmpty();
        assertThat(pr.linkedIssueNumbers()).isEmpty();

        CollectedIssue issue = activity.issues().getFirst();
        assertThat(issue.state()).isEqualTo(GithubState.OPEN);
        assertThat(issue.bodyExcerpt()).isNull();
        assertThat(issue.authorLogin()).isNull();
        assertThat(issue.closedAt()).isNull();
        assertThat(issue.labels()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"pull_requests", "issues"})
    @DisplayName("PR·이슈 상태가 OPEN·CLOSED 가 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsUnknownState(String field) {

        ((ObjectNode) data.path(field).path(0)).put("state", "MERGED");
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("PR 의 연결 이슈 번호 중 양의 int 에 들지 않는 번호는 그 번호만 버리고 나머지는 읽는다")
    void dropsLinkedIssueNumbersOutsidePositiveInt() {

        firstPullRequest().putArray("linked_issue_numbers")
                .add(42).add(2_147_483_648L).add(0).add(-3).add(new BigInteger("99999999999999999999")).add(7);
        responseBody = success(data);

        var activity = adapter.collect(request(List.of()));

        assertThat(activity.pullRequests().getFirst().linkedIssueNumbers()).containsExactly(42, 7);

    }

    @Test
    @DisplayName("PR·이슈의 제목·본문·라벨에 섞인 U+0000 문자는 지우고 읽는다")
    void removesNulFromPullRequestAndIssueText() {

        firstPullRequest().put("title", "세션\0 조회").put("body_excerpt", "인덱스\0 추가");
        firstIssue().put("title", "느린\0 조회").put("body_excerpt", "응답\0 지연");
        firstIssue().putArray("labels").add("perf\0ormance");
        responseBody = success(data);

        var activity = adapter.collect(request(List.of()));

        CollectedPullRequest pr = activity.pullRequests().getFirst();
        assertThat(pr.title()).isEqualTo("세션 조회");
        assertThat(pr.bodyExcerpt()).isEqualTo("인덱스 추가");

        CollectedIssue issue = activity.issues().getFirst();
        assertThat(issue.title()).isEqualTo("느린 조회");
        assertThat(issue.bodyExcerpt()).isEqualTo("응답 지연");
        assertThat(issue.labels()).containsExactly("performance");

    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-sha", "abc12", "0123456789abcdef0123456789abcdef012345678"})
    @DisplayName("PR 커밋 sha 가 7~40자리 16진수가 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMalformedPullRequestCommitSha(String sha) {

        firstPullRequest().putArray("commit_shas").add("abc1234567890").add(sha);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("AI 가 PR 마다 보낸 리뷰와 리뷰마다 보낸 줄 댓글을 칸마다 받은 그대로 읽는다")
    void readsReviewsAndComments() {

        var activity = adapter.collect(request(List.of()));

        assertThat(activity.pullRequests().getFirst().reviews()).hasSize(1);
        CollectedReview review = activity.pullRequests().getFirst().reviews().getFirst();
        assertThat(review.reviewId()).isEqualTo(9001L);
        assertThat(review.state()).isEqualTo(ReviewState.CHANGES_REQUESTED);
        assertThat(review.authorLogin()).isEqualTo("reviewer");
        assertThat(review.bodyExcerpt()).isEqualTo("책임 분리를 명확히 해주세요.");
        assertThat(review.submittedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));

        assertThat(review.comments()).hasSize(1);
        CollectedReviewComment comment = review.comments().getFirst();
        assertThat(comment.commentId()).isEqualTo(9101L);
        assertThat(comment.reviewId()).isEqualTo(9001L);
        assertThat(comment.authorLogin()).isEqualTo("reviewer");
        assertThat(comment.bodyExcerpt()).isEqualTo("인덱스 책임을 repository 계층으로 옮겨주세요.");
        assertThat(comment.path()).isEqualTo("backend/session.sql");
        assertThat(comment.line()).isEqualTo(12);
        assertThat(comment.commentedAt()).isEqualTo(Instant.parse("2026-09-20T11:50:00Z"));

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "\"[]\"", "3"})
    @DisplayName("PR 의 reviews 가 없거나 배열이 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMissingOrNonArrayReviews(String json) {

        replace(firstPullRequest(), "reviews", json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "\"[]\"", "3"})
    @DisplayName("리뷰의 comments 가 없거나 배열이 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMissingOrNonArrayComments(String json) {

        replace(firstReview(), "comments", json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("INT 를 넘는 리뷰 id·줄 댓글 id 도 양의 long 그대로 읽는다")
    void readsReviewAndCommentIdsAboveInt() {

        firstReview().put("review_id", 5_417_303_905L);
        firstComment().put("comment_id", 5_417_303_906L).put("review_id", 5_417_303_905L);
        responseBody = success(data);

        CollectedReview review = collectFirstReview();

        assertThat(review.reviewId()).isEqualTo(5_417_303_905L);
        assertThat(review.comments().getFirst().commentId()).isEqualTo(5_417_303_906L);
        assertThat(review.comments().getFirst().reviewId()).isEqualTo(5_417_303_905L);

    }

    @ParameterizedTest
    @CsvSource({
            "review, review_id, 0", "review, review_id, -1", "review, review_id, 1.5", "review, review_id, \"9001\"",
            "comment, comment_id, 0", "comment, comment_id, -1", "comment, comment_id, 1.5",
            "comment, comment_id, \"9101\"",
            "comment, review_id, 0", "comment, review_id, -1", "comment, review_id, 1.5",
            "comment, review_id, \"9001\""})
    @DisplayName("리뷰 id·줄 댓글 id·줄 댓글의 리뷰 id 가 0·음수·정수가 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsNonPositiveOrNonIntegerReviewAndCommentIds(String owner, String field, String json) {

        replace(owner.equals("review") ? firstReview() : firstComment(), field, json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "null")
    @DisplayName("줄 댓글의 review_id 가 없거나 null 이면 비운 채 읽는다")
    void readsMissingCommentReviewIdAsNull(String json) {

        replace(firstComment(), "review_id", json);
        responseBody = success(data);

        assertThat(collectFirstReview().comments().getFirst().reviewId()).isNull();

    }

    @ParameterizedTest
    @EnumSource(ReviewState.class)
    @DisplayName("리뷰 상태 네 가지는 그 상태 그대로 읽는다")
    void readsEveryKnownReviewState(ReviewState state) {

        firstReview().put("state", state.name());
        responseBody = success(data);

        assertThat(collectFirstReview().state()).isEqualTo(state);

    }

    @Test
    @DisplayName("PENDING 이 아닌 모르는 리뷰 상태는 상태를 비운 채 읽고, 그 리뷰에 달린 줄 댓글은 그대로 읽는다")
    void readsUnknownReviewStateAsNullAndKeepsItsComments() {

        firstReview().put("state", "RESOLVED");
        responseBody = success(data);

        CollectedReview review = collectFirstReview();

        assertThat(review.reviewId()).isEqualTo(9001L);
        assertThat(review.state()).isNull();
        assertThat(review.comments()).extracting(CollectedReviewComment::commentId).containsExactly(9101L);

    }

    @Test
    @DisplayName("제출하지 않은 PENDING 리뷰와 그 리뷰에 달린 줄 댓글은 저장으로 넘기지 않는다")
    void dropsPendingReviewWithItsComments() {

        addComment(addReview(9002, "PENDING"), 9102, 9002);
        responseBody = success(data);

        List<CollectedReview> reviews = adapter.collect(request(List.of())).pullRequests().getFirst().reviews();

        assertThat(reviews).extracting(CollectedReview::reviewId).containsExactly(9001L);
        assertThat(reviews.getFirst().comments()).extracting(CollectedReviewComment::commentId)
                .containsExactly(9101L);

    }

    @Test
    @DisplayName("다른 리뷰에 실려 와도 PENDING 리뷰 id 를 가진 줄 댓글은 저장으로 넘기지 않는다")
    void dropsCommentCarryingPendingReviewId() {

        addReview(9002, "PENDING");
        addComment(firstReview(), 9103, 9002);
        responseBody = success(data);

        assertThat(collectFirstReview().comments()).extracting(CollectedReviewComment::commentId)
                .containsExactly(9101L);

    }

    @Test
    @DisplayName("PENDING 리뷰도 같은 검사를 거쳐 그 리뷰의 줄 댓글 line 이 잘못되면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsPendingReviewWithInvalidComment() {

        addComment(addReview(9002, "PENDING"), 9102, 9002).put("line", 0);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "3", "\"\"", "\"   \""})
    @DisplayName("리뷰 상태가 없거나 문자열이 아니거나 빈 글이면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsMissingOrBlankReviewState(String json) {

        replace(firstReview(), "state", json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("리뷰 본문과 줄 댓글 본문에 섞인 U+0000 문자는 지우고 읽는다")
    void removesNulFromReviewAndCommentBodies() {

        firstReview().put("body_excerpt", "책임\0 분리");
        firstComment().put("body_excerpt", "계층\0 이동");
        responseBody = success(data);

        CollectedReview review = collectFirstReview();

        assertThat(review.bodyExcerpt()).isEqualTo("책임 분리");
        assertThat(review.comments().getFirst().bodyExcerpt()).isEqualTo("계층 이동");

    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "1.5", "\"12\""})
    @DisplayName("줄 댓글의 line 이 0·음수·INT 초과·정수가 아니면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsInvalidCommentLine(String json) {

        replace(firstComment(), "line", json);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("리뷰의 제출 시각·본문·작성자와 줄 댓글의 작성자·path·line 이 null 이면 그 값을 비운 채 읽는다")
    void readsReviewAndCommentWithoutOptionalValues() {

        firstReview().putNull("submitted_at").putNull("body_excerpt").putNull("author_login");
        firstComment().putNull("author_login").putNull("path").putNull("line");
        responseBody = success(data);

        CollectedReview review = collectFirstReview();

        assertThat(review.submittedAt()).isNull();
        assertThat(review.bodyExcerpt()).isNull();
        assertThat(review.authorLogin()).isNull();
        CollectedReviewComment comment = review.comments().getFirst();
        assertThat(comment.authorLogin()).isNull();
        assertThat(comment.path()).isNull();
        assertThat(comment.line()).isNull();
        assertThat(comment.commentedAt()).isEqualTo(Instant.parse("2026-09-20T11:50:00Z"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"body_excerpt", "created_at"})
    @DisplayName("줄 댓글의 본문이나 작성 시각이 없으면 잘못된 AI 응답으로 보고 저장으로 넘기지 않는다")
    void rejectsCommentWithoutBodyOrCreatedAt(String field) {

        firstComment().remove(field);
        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE");

    }

    @Test
    @DisplayName("HTTP 오류 봉투의 code·retryable만 보존하고 원문 오류는 버린다")
    void sanitizesHttpEnvelopeFailure() {
        responseStatus = 502;
        responseBody = """
                {"success":false,"data":null,"meta":{},
                 "error":{"code":"GITHUB_API_ERROR","message":"contract-only-token","retryable":true}}
                """;
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("GITHUB_API_ERROR");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.httpStatus()).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain(TOKEN);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("HTTP 200이어도 실패 봉투를 성공 결과로 처리하지 않는다")
    void rejectsLogicalFailureOnSuccessfulHttpStatus() {
        responseBody = """
                {"success":false,"data":null,"meta":{},
                 "error":{"code":"RESOURCE_NOT_FOUND","message":"missing","retryable":false}}
                """;
        assertError("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("알 수 없는 에러 코드 원문은 예외 메시지로 새지 않는다")
    void rejectsUntrustedErrorCode() {
        responseBody = """
                {"success":false,"error":{"code":"contract-only-token","retryable":true}}
                """;
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("FastAPI validation 오류도 HTTP 오류로 분류하고 detail은 보관하지 않는다")
    void sanitizesNonEnvelopeHttpFailure() {
        responseStatus = 422;
        responseBody = "{\"detail\":\"contract-only-token\"}";
        assertError("AI_HTTP_ERROR");
    }

    @Test
    @DisplayName("JSON이 아닌 성공 응답은 거절한다")
    void rejectsMalformedJson() {
        responseBody = "<html>contract-only-token</html>";
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("철회된 연결로는 AI를 호출하지 않는다")
    void rejectsRevokedConnection() {
        GithubConnection connection = activeConnection(null);
        connection.revoke();
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("만료된 연결로는 AI를 호출하지 않는다")
    void rejectsExpiredConnection() {
        activeConnection(Instant.now().minusSeconds(1));
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 127, 233})
    @DisplayName("NUL·DEL·비ASCII 토큰은 헤더 생성 전에 안전하게 거절한다")
    void rejectsUnsafeTokenWithoutLeakingHeaderValue(int character) {
        String unsafeToken = TOKEN + (char) character;
        GithubConnection connection = GithubConnection.grant(USER_ID,
                new String[]{"read:user", "public_repo"}, cipher.encrypt(unsafeToken), null);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("저장된 commit SHA를 AI 요청에 그대로 전달한다")
    void forwardsKnownCommitShas() throws Exception {
        List<String> knownShas = List.of("a".repeat(40), "b".repeat(40));

        adapter.collect(new IngestRequest(USER_REPOSITORY_ID, List.of(), knownShas));

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(sent.has("since")).isFalse();
        assertThat(sent.path("known_commit_shas")).hasSize(2);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("연결 실패는 재시도 가능한 안전한 전송 오류다")
    void classifiesTransportFailure() {
        server.stop(0);
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getCause()).isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("수집 read timeout은 원문 없이 재시도 가능한 전송 오류로 반환한다")
    void appliesCollectionReadTimeout(boolean headersFirst) {
        responseDelayMs = 500;
        responseHeadersFirst = headersFirst;
        AiHttpClient shortTimeout = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(50)), mapper);
        adapter = new AiRepositoryActivityAdapter(targets,
                new GithubCollectionAccess(users, connections, cipher, shortTimeout, null, null));
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("수집은 다른 AI 호출의 응답 대기 한도가 짧아도 수집 전용 한도까지 기다린다")
    void collectionWaitsForItsOwnReadTimeout() {

        responseDelayMs = 500;
        AiHttpClient shortDefaultTimeout = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofSeconds(2)), mapper);
        adapter = new AiRepositoryActivityAdapter(targets,
                new GithubCollectionAccess(users, connections, cipher, shortDefaultTimeout, null, null));

        assertThat(adapter.collect(request(List.of())).commits()).hasSize(1);

    }

    @Test
    @DisplayName("health는 AI 봉투 검증 없이 원본 JSON을 조회한다")
    void readsRawHealthJsonWithoutToken() {
        responseBody = "{\"status\":\"ok\"}";
        assertThat(http.get("/health").path("status").asString()).isEqualTo("ok");
        assertThat(requestToken).isNull();
        assertThat(requestMethod).isEqualTo("GET");
    }

    private GithubConnection activeConnection(Instant expiry) {
        GithubConnection connection = GithubConnection.grant(USER_ID, new String[]{"read:user", "public_repo"},
                cipher.encrypt(TOKEN), expiry);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        return connection;
    }

    private IngestRequest request(List<String> branches) {
        return new IngestRequest(USER_REPOSITORY_ID, branches, List.of());
    }

    private ObjectNode firstCommit() {

        return (ObjectNode) data.path("commits").path(0);

    }

    private ObjectNode firstFile() {

        return (ObjectNode) firstCommit().path("files").path(0);

    }

    private CollectedCommit collectFirstCommit() {

        return adapter.collect(request(List.of())).commits().getFirst();

    }

    private ObjectNode firstPullRequest() {

        return (ObjectNode) data.path("pull_requests").path(0);

    }

    private ObjectNode firstIssue() {

        return (ObjectNode) data.path("issues").path(0);

    }

    private ObjectNode firstReview() {

        return (ObjectNode) firstPullRequest().path("reviews").path(0);

    }

    private ObjectNode firstComment() {

        return (ObjectNode) firstReview().path("comments").path(0);

    }

    private CollectedReview collectFirstReview() {

        return adapter.collect(request(List.of())).pullRequests().getFirst().reviews().getFirst();

    }

    // 첫 리뷰를 본떠 줄 댓글 없는 리뷰를 첫 PR 의 리뷰 끝에 더한다
    private ObjectNode addReview(long reviewId, String state) {

        ObjectNode review = firstReview().deepCopy();
        review.put("review_id", reviewId).put("state", state).putArray("comments");
        ((ArrayNode) firstPullRequest().path("reviews")).add(review);
        return review;

    }

    // 첫 줄 댓글을 본떠 review 의 줄 댓글 끝에 더한다
    private ObjectNode addComment(ObjectNode review, long commentId, long reviewId) {

        ObjectNode comment = firstComment().deepCopy();
        comment.put("comment_id", commentId).put("review_id", reviewId);
        ((ArrayNode) review.path("comments")).add(comment);
        return comment;

    }

    // json 이 null 이면 칸을 지우고, 아니면 그 JSON 값으로 바꾼다
    private void replace(ObjectNode node, String field, String json) {

        if (json == null) {
            node.remove(field);
            return;
        }
        node.set(field, mapper.readTree(json));

    }

    private String success(ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("error", mapper.nullNode());
        envelope.set("meta", mapper.createObjectNode().put("model", "github-collector")
                .put("tool_calls_made", 5).put("processing_ms", 10));
        return mapper.writeValueAsString(envelope);
    }

    private void assertError(String code) {
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(code);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain(TOKEN);
                });
    }
}
