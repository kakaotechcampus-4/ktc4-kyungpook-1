package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class RecallApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TEAMMATE_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHERS_REPO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID UNKNOWN_REPO = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String[] RECALL_KEYS = {"repoId", "note", "dirs"};
    private static final String[] DIR_KEYS = {"path", "filesChanged", "myCommits", "files", "question", "options"};
    private static final String[] CANDIDATE_KEYS = {"id", "repoId", "type", "status", "ref", "title", "reason", "meta",
            "weak", "score", "commits", "usedByCardId"};
    private static final String NOTE = "어떤 파일을 만졌는지로 기억을 끌어냅니다. 답한 내용은 후보가 되고, 그 후보로 만든 카드의 과제(T) 칸에 그대로 들어갑니다.";
    private static final String NOTHING_NOTE = "바뀐 파일이 저장된 내 커밋이 없어요.";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private TestFixtures fixtures;
    private CandidateBoardFixtures board;
    private ExecutorService otherRequest;
    private Long myUserId;
    private Long teammateUserId;
    private Long repositoryId;
    private Long myRepoId;
    private Long teammateRepoId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        fixtures.clear();
        otherRequest = Executors.newSingleThreadExecutor();

        myUserId = fixtures.insertUser(1L, "grow22");
        teammateUserId = fixtures.insertUser(2L, "teammate");
        Long othersUserId = fixtures.insertUser(3L, "someone-else");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long othersRepository = fixtures.insertRepository(2L, "someone-else", "private-work");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(TEAMMATE_REPO, teammateUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, othersRepository);

    }

    @AfterEach
    void tearDown() {

        otherRequest.shutdownNow();

    }

    @Test
    @DisplayName("조회는 GET /api/repos/{id}/recall 이 폴더 목록을 돌려주고 답은 POST /api/repos/{id}/recall/answers 가 후보 한 줄을 돌려준다")
    void servesBothRoutes() throws Exception {

        recall(myUserId, MY_REPO.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.error").value(nullValue()));
        answer(myUserId, MY_REPO.toString(), "/main/java/", "로그인을 맡았다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("MANUAL"))
                .andExpect(jsonPath("$.error").value(nullValue()));

    }

    @Test
    @DisplayName("로그인하지 않으면 조회는 401 이고 CSRF 를 붙여도 답은 401 이며 후보가 생기지 않는다")
    void rejectsAnonymousRequests() throws Exception {

        mvc.perform(get("/api/repos/{id}/recall", MY_REPO)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/repos/{id}/recall/answers", MY_REPO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("/main/java/", "답")))
                .andExpect(status().isUnauthorized());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("로그인했어도 CSRF 헤더 없이 답을 보내면 403 이고 후보가 생기지 않는다")
    void requiresCsrfToken() throws Exception {

        mvc.perform(post("/api/repos/{id}/recall/answers", MY_REPO).with(loggedInAs(myUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("/main/java/", "답")))
                .andExpect(status().isForbidden());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("UUID 가 아닌 id 와 없는 저장소와 남의 저장소는 조회도 답도 똑같은 본문의 404 NOT_FOUND 이고 후보가 생기지 않는다")
    void answersTheSame404ForMalformedUnknownAndForeign() throws Exception {

        String malformed = body(recall(myUserId, "not-a-uuid").andExpect(status().isNotFound()));
        String unknown = body(recall(myUserId, UNKNOWN_REPO.toString()).andExpect(status().isNotFound()));
        String foreign = body(recall(myUserId, OTHERS_REPO.toString()).andExpect(status().isNotFound()));
        String malformedAnswer = body(answer(myUserId, "not-a-uuid", "/a/", "답").andExpect(status().isNotFound()));
        String unknownAnswer = body(answer(myUserId, UNKNOWN_REPO.toString(), "/a/", "답").andExpect(status().isNotFound()));
        String foreignAnswer = body(answer(myUserId, OTHERS_REPO.toString(), "/a/", "답").andExpect(status().isNotFound()));

        assertThat(JsonPath.<String>read(malformed, "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<String>read(malformed, "$.error.message")).isEqualTo("저장소를 찾을 수 없습니다.");
        assertThat(JsonPath.<Object>read(malformed, "$.data")).isNull();
        assertThat(List.of(unknown, foreign, malformedAnswer, unknownAnswer, foreignAnswer)).containsOnly(malformed);
        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("남의 저장소를 조회하면 VIEW_RECALL, 답을 보내면 CREATE_CANDIDATE 거절 기록이 그 저장소 연결 id 로 한 줄씩 남는다")
    void recordsDeniedEventsForForeignRepository() throws Exception {

        recall(myUserId, OTHERS_REPO.toString()).andExpect(status().isNotFound());
        answer(myUserId, OTHERS_REPO.toString(), "/a/", "답").andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_RECALL", othersRepoId, "DENIED"),
                new AuditRow(myUserId, "CREATE_CANDIDATE", othersRepoId, "DENIED"));

    }

    @Test
    @DisplayName("잘못된 id 나 없는 저장소에는 기록을 남기지 않는다")
    void recordsNothingForMalformedOrUnknown() throws Exception {

        recall(myUserId, "not-a-uuid").andExpect(status().isNotFound());
        recall(myUserId, UNKNOWN_REPO.toString()).andExpect(status().isNotFound());
        answer(myUserId, "not-a-uuid", "/a/", "답").andExpect(status().isNotFound());
        answer(myUserId, UNKNOWN_REPO.toString(), "/a/", "답").andExpect(status().isNotFound());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("내 저장소에 조회하고 답해서 성공하면 감사 기록을 남기지 않는다")
    void recordsNothingOnSuccess() throws Exception {

        recall(myUserId, MY_REPO.toString()).andExpect(status().isOk());
        answer(myUserId, MY_REPO.toString(), "/a/", "답").andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("GitHub 연결이 없어도 조회와 답이 200 이다")
    void worksWithoutGithubConnection() throws Exception {

        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_connection", Long.class)).isZero();

        recall(myUserId, MY_REPO.toString()).andExpect(status().isOk());
        answer(myUserId, MY_REPO.toString(), "/a/", "답").andExpect(status().isOk());

    }

    @Test
    @DisplayName("내 커밋이 바꾼 파일을 폴더로 묶어 내 커밋이 많은 순으로 돌려주고 팀원·봇 커밋과 건너뛸 파일은 세지 않는다")
    void listsDirsOfMyCommits() throws Exception {

        Long first = board.insertCommitBy(repositoryId, "a".repeat(40), "GROW22", null);
        Long second = board.insertCommitBy(repositoryId, "b".repeat(40), "grow22", null);
        board.insertFile(first, "src/main/java/com/App.java");
        board.insertFile(first, "src/main/java/com/Util.java");
        board.insertFile(first, "README.md");
        board.insertFile(first, "build/out/Gen.java");
        board.insertFile(second, "src/main/java/com/App.java");
        board.insertFile(board.insertCommitBy(repositoryId, "c".repeat(40), "teammate", null), "src/main/java/com/Other.java");
        board.insertFile(board.insertCommitBy(repositoryId, "d".repeat(40), "grow22", "BOT"), "docs/guide/Bot.md");

        String json = body(recall(myUserId, MY_REPO.toString()).andExpect(status().isOk()));
        Map<String, Object> data = JsonPath.read(json, "$.data");

        assertThat(data).containsOnlyKeys(RECALL_KEYS);
        assertThat(JsonPath.<String>read(json, "$.data.note")).isEqualTo(NOTE);
        assertThat(JsonPath.<List<Object>>read(json, "$.data.dirs")).hasSize(2);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.data.dirs[0]")).containsOnlyKeys(DIR_KEYS);
        assertThat(JsonPath.<String>read(json, "$.data.dirs[0].path")).isEqualTo("/java/com/");
        assertThat(JsonPath.<Integer>read(json, "$.data.dirs[0].filesChanged")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.data.dirs[0].myCommits")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(json, "$.data.dirs[0].files")).containsExactly("App.java", "Util.java");
        assertThat(JsonPath.<String>read(json, "$.data.dirs[0].question"))
                .isEqualTo("이 파일들을 만졌을 때, 무엇을 해내려던 중이었나요?");
        assertThat(JsonPath.<List<String>>read(json, "$.data.dirs[0].options"))
                .containsExactly("기능을 맡아 만들었어요", "팀원 코드를 이어받았어요", "급하게 버그를 고쳤어요");
        assertThat(JsonPath.<String>read(json, "$.data.dirs[1].path")).isEqualTo("/");
        assertThat(JsonPath.<Integer>read(json, "$.data.dirs[1].myCommits")).isEqualTo(1);

    }

    @Test
    @DisplayName("파일이 저장된 내 커밋이 없으면 200 이고 dirs 는 빈 목록이며 안내 글은 그 사실만 말한다")
    void answersEmptyDirsWithFactualNote() throws Exception {

        board.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null);
        board.insertFile(board.insertCommitBy(repositoryId, "b".repeat(40), "teammate", null), "src/Other.java");

        recall(myUserId, MY_REPO.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value(NOTHING_NOTE))
                .andExpect(jsonPath("$.data.dirs", hasSize(0)));

    }

    @Test
    @DisplayName("후보가 이미 있는 저장소도 같은 규칙으로 폴더 목록을 준다")
    void listsDirsEvenWhenCandidatesExist() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        board.insertFile(board.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null), "src/api/Login.java");

        recall(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.note").value(NOTE))
                .andExpect(jsonPath("$.data.dirs", hasSize(1)))
                .andExpect(jsonPath("$.data.dirs[0].path").value("/src/api/"));

    }

    @Test
    @DisplayName("본문이 없거나 JSON 이 아니거나 칸 모양이 틀리면 400 INVALID_REQUEST 이고 후보가 생기지 않는다")
    void rejectsMalformedBodies() throws Exception {

        for (String content : new String[]{"", "not json", "{\"path\": [], \"text\": \"답\"}", "{\"path\": \"/a/\", \"text\": {}}"}) {
            mvc.perform(post("/api/repos/{id}/recall/answers", MY_REPO).with(loggedInAs(myUserId)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("남의 저장소에 틀린 답을 보내면 400 이 아니라 404 이고 거절 기록이 남는다")
    void answersNotFoundBeforeInvalid() throws Exception {

        answer(myUserId, OTHERS_REPO.toString(), "/a/", "   ").andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CANDIDATE", othersRepoId, "DENIED"));

    }

    @ParameterizedTest
    @MethodSource("blankTexts")
    @DisplayName("text 가 비었거나 공백·NBSP·U+2007·U+202F·U+FEFF 뿐이면 400 이고 후보가 생기지 않는다")
    void rejectsBlankText(String text) throws Exception {

        answer(myUserId, MY_REPO.toString(), "/a/", text)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(candidateCount()).isZero();

    }

    static Stream<Named<String>> blankTexts() {

        return Stream.of(
                Named.of("빈 글", ""),
                Named.of("공백뿐", "   "),
                Named.of("NBSP 뿐", "\u00A0"),
                Named.of("U+2007 뿐", "\u2007"),
                Named.of("U+202F 뿐", "\u202F"),
                Named.of("BOM 뿐", "\uFEFF"));

    }

    @Test
    @DisplayName("text 나 path 칸이 아예 없거나 null 이어도 400 이다")
    void rejectsMissingFields() throws Exception {

        mvc.perform(post("/api/repos/{id}/recall/answers", MY_REPO).with(loggedInAs(myUserId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        answer(myUserId, MY_REPO.toString(), "/a/", null).andExpect(status().isBadRequest());
        answer(myUserId, MY_REPO.toString(), null, "답").andExpect(status().isBadRequest());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("text 는 300자까지 받고 301자는 400 이다")
    void limitsTextTo300() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/a/", "a".repeat(301)).andExpect(status().isBadRequest());
        assertThat(candidateCount()).isZero();

        answer(myUserId, MY_REPO.toString(), "/a/", "a".repeat(300)).andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("path 는 197자까지 받고 198자는 400 이며 비었어도 400 이다")
    void limitsPathTo197() throws Exception {

        answer(myUserId, MY_REPO.toString(), "p".repeat(198), "답").andExpect(status().isBadRequest());
        answer(myUserId, MY_REPO.toString(), "  ", "답").andExpect(status().isBadRequest());
        assertThat(candidateCount()).isZero();

        answer(myUserId, MY_REPO.toString(), "p".repeat(197), "답").andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("text 나 path 에 U+0000 이 있으면 400 이고 아무것도 저장하지 않는다")
    void rejectsNul() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/a/", "가\u0000나").andExpect(status().isBadRequest());
        answer(myUserId, MY_REPO.toString(), "/a\u0000/", "답").andExpect(status().isBadRequest());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("지금 회상 목록에 없는 path 로 답해도 후보가 된다")
    void acceptsPathOutsideTheList() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/nowhere/", "답")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("/nowhere/ 작업"));

    }

    @Test
    @DisplayName("답은 앞뒤 공백까지 받은 그대로 후보의 이유가 된다")
    void keepsTextAsReceived() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/a/", "  로그인을 맡았다 \n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("  로그인을 맡았다 \n"));

        assertThat(jdbc.queryForObject("SELECT reason_text FROM candidate", String.class)).isEqualTo("  로그인을 맡았다 \n");

    }

    @Test
    @DisplayName("새 후보 행은 MANUAL·NEW·손댄 표시 켬·ref RECALL·제목 'path 작업' 이고 PR 번호·점수·묶음 숫자는 비어 있으며 커밋 연결이 없다")
    void storesManualRecallRow() throws Exception {

        board.insertFile(board.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null), "src/api/Login.java");

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT user_repository_id, source_type, github_pr_number, source_ref, cardinality(pull_request_numbers) AS pr_len,
                       cardinality(issue_numbers) AS issue_len, title, reason_text, score, low_card_worth, file_count,
                       review_comment_count, span_days, code_ratio, status, is_user_modified
                FROM candidate
                """);
        assertThat(row.get("user_repository_id")).isEqualTo(myRepoId);
        assertThat(row.get("source_type")).isEqualTo("MANUAL");
        assertThat(row.get("github_pr_number")).isNull();
        assertThat(row.get("source_ref")).isEqualTo("RECALL");
        assertThat(row.get("pr_len")).isEqualTo(0);
        assertThat(row.get("issue_len")).isEqualTo(0);
        assertThat(row.get("title")).isEqualTo("/src/api/ 작업");
        assertThat(row.get("reason_text")).isEqualTo("로그인을 맡았다");
        assertThat(row.get("score")).isNull();
        assertThat(row.get("low_card_worth")).isEqualTo(false);
        assertThat(row.get("file_count")).isEqualTo(0);
        assertThat(row.get("review_comment_count")).isEqualTo(0);
        assertThat(row.get("span_days")).isNull();
        assertThat(row.get("code_ratio")).isNull();
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("is_user_modified")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_commit", Long.class)).isZero();

    }

    @Test
    @DisplayName("응답은 200 이고 MANUAL·NEW·ref RECALL·meta 0·0·0·null·null·weak false·score 0·commits null·usedByCardId null 이다")
    void answersCandidateShape() throws Exception {

        String json = body(answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk()));
        Map<String, Object> data = JsonPath.read(json, "$.data");

        assertThat(data).containsOnlyKeys(CANDIDATE_KEYS);
        assertThat(data.get("id")).isEqualTo(jdbc.queryForObject("SELECT public_id::text FROM candidate", String.class));
        assertThat(data.get("repoId")).isEqualTo(MY_REPO.toString());
        assertThat(data.get("type")).isEqualTo("MANUAL");
        assertThat(data.get("status")).isEqualTo("NEW");
        assertThat(data.get("ref")).isEqualTo("RECALL");
        assertThat(data.get("title")).isEqualTo("/src/api/ 작업");
        assertThat(data.get("reason")).isEqualTo("로그인을 맡았다");
        assertThat(data.get("weak")).isEqualTo(false);
        assertThat(((Number) data.get("score")).doubleValue()).isZero();
        assertThat(data.get("commits")).isNull();
        assertThat(data.get("usedByCardId")).isNull();
        Map<String, Object> meta = JsonPath.read(json, "$.data.meta");
        assertThat(meta).containsOnlyKeys("commits", "files", "reviewComments", "days", "codeRatio");
        assertThat(meta.get("commits")).isEqualTo(0);
        assertThat(meta.get("files")).isEqualTo(0);
        assertThat(meta.get("reviewComments")).isEqualTo(0);
        assertThat(meta.get("days")).isNull();
        assertThat(meta.get("codeRatio")).isNull();

    }

    @Test
    @DisplayName("주소에 대문자 UUID 를 써도 응답의 repoId 는 목록이 준 소문자 id 와 같다")
    void answersCanonicalRepoId() throws Exception {

        UUID lettered = UUID.fromString("abcdefab-cdef-4bcd-8fab-cdefabcdefab");
        fixtures.insertUserRepository(lettered, myUserId, fixtures.insertRepository(3L, "grow22", "lettered"));

        answer(myUserId, lettered.toString().toUpperCase(), "/src/api/", "로그인을 맡았다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repoId").value(lettered.toString()));

    }

    @Test
    @DisplayName("응답은 바로 다음 보드의 같은 줄과 똑같다")
    void matchesTheBoardLine() throws Exception {

        String answered = body(answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk()));

        String boardBody = body(mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(status().isOk()));

        assertThat(JsonPath.<Map<String, Object>>read(boardBody, "$.data.candidates[0]"))
                .isEqualTo(JsonPath.<Map<String, Object>>read(answered, "$.data"));

    }

    @Test
    @DisplayName("회상 후보가 생기면 빈 보드가 OK 가 되고 회상 후보는 점수 있는 후보 뒤에 놓인다")
    void turnsEmptyBoardIntoOkAndSortsAfterScored() throws Exception {

        mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"));

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        board.insertCandidate(myRepoId, "PR", 42, "NEW", "점수 있는 후보", "0.1");

        mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.candidates[0].title").value("점수 있는 후보"))
                .andExpect(jsonPath("$.data.candidates[1].title").value("/src/api/ 작업"));

    }

    @Test
    @DisplayName("같은 폴더에 같은 답을 다시 보내면 새로 만들지 않고 처음 후보를 200 으로 돌려준다")
    void returnsSameCandidateForSameAnswer() throws Exception {

        String first = body(answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk()));
        String again = body(answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(candidateCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("답이나 폴더가 다르면 새 후보를 만든다")
    void createsNewCandidateForDifferentAnswerOrPath() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다.").andExpect(status().isOk());
        answer(myUserId, MY_REPO.toString(), "/src/ui/", "로그인을 맡았다").andExpect(status().isOk());

        assertThat(candidateCount()).isEqualTo(3);

    }

    @Test
    @DisplayName("같은 답의 후보가 NEW 가 아니거나 다른 저장소 연결의 것이거나 회상 후보가 아니면 새로 만든다")
    void createsNewCandidateWhenExistingOneDoesNotQualify() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        jdbc.update("UPDATE candidate SET status = 'EXCLUDED'");

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(2);

        answer(teammateUserId, TEAMMATE_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE user_repository_id = ?", Long.class,
                teammateRepoId)).isEqualTo(1);

        Long manualWithoutRecall = board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "/src/db/ 작업", null);
        jdbc.update("UPDATE candidate SET reason_text = '다른 곳에서 만든 후보' WHERE id = ?", manualWithoutRecall);
        answer(myUserId, MY_REPO.toString(), "/src/db/", "다른 곳에서 만든 후보").andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(5);

    }

    @Test
    @DisplayName("묶은 기록을 만들거나 바꾸지 않는다")
    void leavesGroupingRecordAlone() throws Exception {

        answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_grouping", Long.class)).isZero();

        Instant groupedAt = Instant.parse("2026-10-01T00:00:00Z");
        board.insertGrouping(myRepoId, groupedAt);

        answer(myUserId, MY_REPO.toString(), "/src/ui/", "화면을 맡았다").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT input_fingerprint FROM candidate_grouping", String.class))
                .isEqualTo("0".repeat(64));
        assertThat(jdbc.queryForObject("SELECT grouped_at FROM candidate_grouping", java.sql.Timestamp.class).toInstant())
                .isEqualTo(groupedAt);

    }

    @Test
    @DisplayName("다시 분석 저장이 후보 행을 잠근 동안에도 기다리지 않고 답이 저장된다")
    void doesNotWaitForLockedCandidates() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "분석이 잠근 후보", "0.9");

        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement lock = locker.prepareStatement(
                    "SELECT id FROM candidate WHERE user_repository_id = ? FOR UPDATE")) {
                lock.setLong(1, myRepoId);
                lock.executeQuery();

                Future<ResultActions> answered = otherRequest.submit(
                        () -> answer(myUserId, MY_REPO.toString(), "/src/api/", "로그인을 맡았다"));

                answered.get(10, TimeUnit.SECONDS).andExpect(status().isOk());
            } finally {
                locker.rollback();
            }
        }

        assertThat(candidateCount()).isEqualTo(2);

    }

    private ResultActions recall(Long userId, String id) throws Exception {

        return mvc.perform(get("/api/repos/{id}/recall", id).with(loggedInAs(userId)));

    }

    private ResultActions answer(Long userId, String id, String path, String text) throws Exception {

        MockHttpServletRequestBuilder request = post("/api/repos/{id}/recall/answers", id)
                .with(loggedInAs(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOf(path, text));

        return mvc.perform(request);

    }

    private String bodyOf(String path, String text) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", path);
        body.put("text", text);

        return json.writeValueAsString(body);

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        String login = userId.equals(myUserId) ? "grow22" : "teammate";
        LoginUser principal = new LoginUser(userId, login, null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private static String body(ResultActions result) throws Exception {

        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    }

    private long candidateCount() {

        return jdbc.queryForObject("SELECT count(*) FROM candidate", Long.class);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
