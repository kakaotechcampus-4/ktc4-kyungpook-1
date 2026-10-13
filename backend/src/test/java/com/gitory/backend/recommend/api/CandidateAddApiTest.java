package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.recommend.domain.LockedSources;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
class CandidateAddApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TEAMMATE_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHERS_REPO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID UNKNOWN_REPO = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String[] CANDIDATE_KEYS = {"id", "repoId", "type", "status", "ref", "title", "reason", "meta",
            "weak", "score", "commits", "usedByCardId"};
    private static final String[] META_KEYS = {"commits", "files", "reviewComments", "days", "codeRatio"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidateStoreService store;

    private final JsonMapper json = JsonMapper.builder().build();

    private TestFixtures fixtures;
    private CandidateBoardFixtures board;
    private Long myUserId;
    private Long teammateUserId;
    private Long repositoryId;
    private Long othersRepository;
    private Long myRepoId;
    private Long teammateRepoId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        teammateUserId = fixtures.insertUser(2L, "teammate");
        Long othersUserId = fixtures.insertUser(3L, "someone-else");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        othersRepository = fixtures.insertRepository(2L, "someone-else", "private-work");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(TEAMMATE_REPO, teammateUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, othersRepository);

    }

    @Test
    @DisplayName("로그인하지 않으면 CSRF 를 붙여도 401 이고 후보가 생기지 않는다")
    void rejectsAnonymousRequest() throws Exception {

        mvc.perform(post("/api/repos/{id}/candidates", MY_REPO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("제목", "요약", List.of())))
                .andExpect(status().isUnauthorized());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("로그인했어도 CSRF 헤더가 없으면 403 이고 후보가 생기지 않는다")
    void requiresCsrfToken() throws Exception {

        mvc.perform(post("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("제목", "요약", List.of())))
                .andExpect(status().isForbidden());

        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("UUID 가 아닌 id 와 없는 저장소와 남의 저장소는 똑같은 본문의 404 NOT_FOUND 이고 후보가 생기지 않는다")
    void answersTheSame404ForMalformedUnknownAndForeign() throws Exception {

        String malformed = body(add(myUserId, "not-a-uuid", "제목", "요약", List.of()).andExpect(status().isNotFound()));
        String unknown = body(add(myUserId, UNKNOWN_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isNotFound()));
        String foreign = body(add(myUserId, OTHERS_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isNotFound()));

        assertThat(JsonPath.<String>read(malformed, "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<String>read(malformed, "$.error.message")).isEqualTo("저장소를 찾을 수 없습니다.");
        assertThat(JsonPath.<Object>read(malformed, "$.data")).isNull();
        assertThat(List.of(unknown, foreign)).containsOnly(malformed);
        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("남의 저장소에는 CREATE_CANDIDATE 거절 기록이 그 저장소 연결 id 로 한 줄 남고 잘못된 id 나 없는 저장소에는 남지 않는다")
    void recordsDeniedEventOnlyForForeignRepository() throws Exception {

        add(myUserId, "not-a-uuid", "제목", "요약", List.of()).andExpect(status().isNotFound());
        add(myUserId, UNKNOWN_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isNotFound());
        assertThat(events()).isEmpty();

        add(myUserId, OTHERS_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CANDIDATE", othersRepoId, "DENIED"));

    }

    @Test
    @DisplayName("성공한 직접 추가는 감사 기록을 남기지 않고 GitHub 연결이 없어도 200 이다")
    void recordsNothingOnSuccessAndNeedsNoGithubConnection() throws Exception {

        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_connection", Long.class)).isZero();

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("남의 저장소에 틀린 본문을 보내면 400 이 아니라 404 이고 거절 기록이 남는다")
    void checksRepositoryBeforeBody() throws Exception {

        add(myUserId, OTHERS_REPO.toString(), "", "", List.of("zzz")).andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CANDIDATE", othersRepoId, "DENIED"));
        assertThat(candidateCount()).isZero();

    }

    @Test
    @DisplayName("본문이 없거나 JSON 이 아니거나 칸 모양이 틀리면 400 INVALID_REQUEST 이고 후보가 생기지 않는다")
    void rejectsMalformedBodies() throws Exception {

        for (String content : List.of("", "not json", "[]", "{\"title\":\"제목\",\"summary\":\"요약\",\"shas\":\"abc\"}")) {
            String answer = body(mvc.perform(post("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest()));
            assertThat(JsonPath.<String>read(answer, "$.error.code")).isEqualTo("INVALID_REQUEST");
        }

        assertThat(candidateCount()).isZero();

    }

    @ParameterizedTest
    @MethodSource("badTexts")
    @DisplayName("제목이 없거나 빈 글이거나 200자를 넘거나 U+0000 이 있으면 400 이고 후보가 생기지 않는다")
    void rejectsBadTitles(String title) throws Exception {

        add(myUserId, MY_REPO.toString(), title, "요약", List.of()).andExpect(status().isBadRequest());

        assertThat(candidateCount()).isZero();

    }

    @ParameterizedTest
    @MethodSource("badTexts")
    @DisplayName("요약이 없거나 빈 글이거나 300자를 넘거나 U+0000 이 있으면 400 이고 후보가 생기지 않는다")
    void rejectsBadSummaries(String summary) throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", summary, List.of()).andExpect(status().isBadRequest());

        assertThat(candidateCount()).isZero();

    }

    static Stream<Named<String>> badTexts() {

        return Stream.of(
                Named.of("null", null),
                Named.of("빈 글", ""),
                Named.of("공백", "   "),
                Named.of("NBSP", " "),
                Named.of("U+2007", " "),
                Named.of("U+202F", " "),
                Named.of("U+FEFF", "﻿"),
                Named.of("U+0000", "가\u0000나"),
                Named.of("301자", "가".repeat(301)));

    }

    @Test
    @DisplayName("제목은 200자까지 받고 201자는 400 이며 받은 글의 앞뒤 공백도 그대로 저장한다")
    void keepsTitleAsReceivedUpTo200() throws Exception {

        add(myUserId, MY_REPO.toString(), "가".repeat(201), "요약", List.of()).andExpect(status().isBadRequest());
        add(myUserId, MY_REPO.toString(), "  " + "가".repeat(196) + "  ", "요약", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("  " + "가".repeat(196) + "  "));

        assertThat(jdbc.queryForObject("SELECT title FROM candidate", String.class))
                .isEqualTo("  " + "가".repeat(196) + "  ");

    }

    @Test
    @DisplayName("요약은 300자까지 받고 301자는 400 이며 후보의 이유가 요약 그대로 저장된다")
    void keepsSummaryAsReceivedUpTo300() throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", "나".repeat(301), List.of()).andExpect(status().isBadRequest());
        add(myUserId, MY_REPO.toString(), "제목", " " + "나".repeat(298) + " ", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value(" " + "나".repeat(298) + " "));

        assertThat(jdbc.queryForObject("SELECT reason_text FROM candidate", String.class))
                .isEqualTo(" " + "나".repeat(298) + " ");

    }

    @Test
    @DisplayName("shas 가 없거나 null 이거나 빈 목록이면 커밋 없는 후보를 만들고 같은 sha 는 한 번만 붙이며 보낸 순서를 지킨다")
    void attachesCommitsInSentOrderOnce() throws Exception {

        Long first = ownCommit('a');
        Long second = ownCommit('b');
        Long third = ownCommit('c');

        add(myUserId, MY_REPO.toString(), "없음 1", "요약", null).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "없음 2", "요약", List.of()).andExpect(status().isOk());
        mvc.perform(post("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"없음 3\",\"summary\":\"요약\",\"shas\":null}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_commit", Long.class)).isZero();

        add(myUserId, MY_REPO.toString(), "순서", "요약", List.of(sha('c'), sha('a'), sha('c'), sha('b')))
                .andExpect(status().isOk());

        assertThat(commitIdsOf("순서")).containsExactly(third, first, second);

    }

    @Test
    @DisplayName("이 저장소의 내 커밋이 아닌 sha(없는 것·팀원·작성자 계정 없음·봇·머지·잠금 파일·null)가 하나라도 있으면 400 이고 후보도 연결도 만들지 않는다")
    void rejectsShasThatAreNotMyCommitsOfThisRepository() throws Exception {

        ownCommit('a');
        board.insertCommitBy(repositoryId, sha('b'), "teammate", null);
        board.insertCommitBy(repositoryId, sha('c'), null, null);
        board.insertCommitBy(repositoryId, sha('d'), "grow22", "BOT");
        board.insertCommitBy(repositoryId, sha('e'), "grow22", "MERGE_COMMIT");
        board.insertCommitBy(repositoryId, sha('f'), "grow22", "LOCKFILE_ONLY");
        board.insertCommitBy(othersRepository, sha('2'), "grow22", null);

        for (String bad : List.of(sha('b'), sha('c'), sha('d'), sha('e'), sha('f'), sha('2'), sha('9'),
                "a".repeat(7))) {
            add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"summary\":\"요약\",\"shas\":[\"" + sha('a') + "\",null]}"))
                .andExpect(status().isBadRequest());

        assertThat(candidateCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_commit", Long.class)).isZero();

    }

    @Test
    @DisplayName("작성자 로그인은 대소문자를 가리지 않고 40자보다 짧게 저장된 sha 도 그대로 보내면 맞는다")
    void matchesLoginIgnoringCaseAndShortStoredShas() throws Exception {

        board.insertCommitBy(repositoryId, sha('a'), "GROW22", null);
        board.insertCommitBy(repositoryId, "abc", "Grow22", null);

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), "abc")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.commits").value(2));

    }

    @Test
    @DisplayName("다른 후보에 든 내 커밋도 붙일 수 있고 그 다른 후보는 그대로다")
    void leavesOtherCandidatesAlone() throws Exception {

        Long commit = ownCommit('a');
        Long other = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "다른 묶음", "0.5");
        board.insertCandidateCommit(other, commit, true, "src/a.java");
        String before = candidateText(other);

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'))).andExpect(status().isOk());

        assertThat(candidateText(other)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT is_included FROM candidate_commit WHERE candidate_id = ?", Boolean.class,
                other)).containsExactly(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_commit WHERE commit_id = ?", Long.class, commit))
                .isEqualTo(2);

    }

    @Test
    @DisplayName("새 후보 행은 MANUAL·NEW·손댄 표시 켬이다")
    void createsManualNewTouchedCandidate() throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_type, status, is_user_modified, user_repository_id FROM candidate");
        assertThat(row).containsEntry("source_type", "MANUAL").containsEntry("status", "NEW")
                .containsEntry("is_user_modified", true).containsEntry("user_repository_id", myRepoId);

    }

    @Test
    @DisplayName("대표 PR 번호는 비고 source_ref 는 MANUAL 이며 응답의 ref 도 MANUAL 이다")
    void hasNoPullRequestAndManualRef() throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of())
                .andExpect(jsonPath("$.data.ref").value("MANUAL"));

        Map<String, Object> row = jdbc.queryForMap("SELECT github_pr_number, source_ref FROM candidate");
        assertThat(row.get("github_pr_number")).isNull();
        assertThat(row.get("source_ref")).isEqualTo("MANUAL");

    }

    @Test
    @DisplayName("PR·이슈 번호 목록은 빈 배열이고 리뷰 코멘트는 0 이라, 붙인 커밋만 다음 경험 그룹화 요청에서 빠지고 PR 번호는 막지 않는다")
    void lockedOnlyByAttachedCommits() throws Exception {

        ownCommit('a');
        ownCommit('b');

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'))).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT cardinality(pull_request_numbers) AS prs, cardinality(issue_numbers) AS issues, review_comment_count FROM candidate");
        assertThat(row).containsEntry("prs", 0).containsEntry("issues", 0).containsEntry("review_comment_count", 0);
        LockedSources locked = store.lockedSources(myRepoId);
        assertThat(locked.commitShas()).containsExactly(sha('a'));
        assertThat(locked.pullRequestNumbers()).isEmpty();

    }

    @Test
    @DisplayName("점수는 비어 있어 응답에서는 0 이고 보드의 맨 뒤에 놓이며 카드감 낮음이 아니다")
    void hasNoScoreAndComesLastOnBoard() throws Exception {

        board.insertCandidate(myRepoId, "PR", 1, "NEW", "높은 점수", "0.9");
        board.insertCandidate(myRepoId, "PR", 2, "NEW", "낮은 점수", "0.1");

        add(myUserId, MY_REPO.toString(), "직접 추가", "요약", List.of())
                .andExpect(jsonPath("$.data.score").value(0.0))
                .andExpect(jsonPath("$.data.weak").value(false));

        assertThat(jdbc.queryForObject("SELECT score FROM candidate WHERE title = '직접 추가'", Object.class)).isNull();
        mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data.candidates[0].title").value("높은 점수"))
                .andExpect(jsonPath("$.data.candidates[1].title").value("낮은 점수"))
                .andExpect(jsonPath("$.data.candidates[2].title").value("직접 추가"));

    }

    @Test
    @DisplayName("붙인 커밋은 USER 출처·포함이고 표시 경로는 바뀐 줄이 가장 많은 파일, 같으면 경로순, 파일이 없으면 빈 글자다")
    void storesAttachedCommitsAsUserWithDisplayPath() throws Exception {

        Long biggest = ownCommit('a');
        board.insertFile(biggest, "small.txt", 1, 0);
        board.insertFile(biggest, "src/Big.java", 40, 10);
        Long tie = ownCommit('b');
        board.insertFile(tie, "z.java", 5, 0);
        board.insertFile(tie, "b.java", 3, 2);
        ownCommit('c');

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), sha('b'), sha('c'))).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT origin FROM candidate_commit ORDER BY id", String.class))
                .containsExactly("USER", "USER", "USER");
        assertThat(jdbc.queryForList("SELECT is_included FROM candidate_commit ORDER BY id", Boolean.class))
                .containsExactly(true, true, true);
        assertThat(jdbc.queryForList("SELECT display_path FROM candidate_commit ORDER BY id", String.class))
                .containsExactly("src/Big.java", "b.java", "");

    }

    @Test
    @DisplayName("meta 는 붙인 커밋으로 계산하며 서로 다른 경로 수, 첫 커밋부터 마지막 커밋까지 일수, 코드 파일이 바뀐 줄의 비율을 담는다")
    void computesMetaFromAttachedCommits() throws Exception {

        Long first = ownCommit('a');
        board.setAuthoredAt(first, Instant.parse("2026-09-01T00:00:00Z"));
        board.insertFile(first, "src/App.java", 10, 0);
        board.insertFile(first, "docs/README.md", 5, 5);
        Long second = ownCommit('b');
        board.setAuthoredAt(second, Instant.parse("2026-09-04T12:00:00Z"));
        board.insertFile(second, "src/App.java", 2, 0);

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), sha('b'))).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT file_count, span_days, code_ratio, review_comment_count FROM candidate");
        assertThat(row).containsEntry("file_count", 2).containsEntry("span_days", 3)
                .containsEntry("review_comment_count", 0);
        assertThat(row.get("code_ratio")).hasToString("0.5455");

    }

    @Test
    @DisplayName("커밋이 없으면 파일 0·기간 null·코드 비율 null 이다")
    void hasEmptyMetaWithoutCommits() throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("SELECT file_count, span_days, code_ratio FROM candidate");
        assertThat(row.get("file_count")).isEqualTo(0);
        assertThat(row.get("span_days")).isNull();
        assertThat(row.get("code_ratio")).isNull();

    }

    @Test
    @DisplayName("응답은 후보 12개 키와 meta 5개 키를 모두 담고 commits 와 usedByCardId 는 null, meta.commits 는 붙인 수이며 직후 보드의 그 줄과 같다")
    void returnsTheBoardLine() throws Exception {

        ownCommit('a');
        ownCommit('b');

        String created = body(add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), sha('b')))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("MANUAL"))
                .andExpect(jsonPath("$.data.status").value("NEW"))
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data.commits").value(nullValue()))
                .andExpect(jsonPath("$.data.usedByCardId").value(nullValue()))
                .andExpect(jsonPath("$.data.meta.commits").value(2)));
        String boardBody = body(mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId))));

        Map<String, Object> line = JsonPath.read(created, "$.data");
        assertThat(line).containsOnlyKeys(CANDIDATE_KEYS);
        assertThat(JsonPath.<Map<String, Object>>read(created, "$.data.meta")).containsOnlyKeys(META_KEYS);
        assertThat(JsonPath.<List<Object>>read(boardBody, "$.data.candidates")).singleElement().isEqualTo(line);

    }

    @Test
    @DisplayName("다시 분석이 후보 행을 잠근 동안에도 직접 추가한 후보는 기다리지 않고 저장된다")
    void doesNotLockExistingCandidates() throws Exception {

        board.insertCandidate(myRepoId, "PR", 1, "NEW", "분석이 잠근 후보", "0.9");

        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement lock = locker.prepareStatement(
                    "SELECT id FROM candidate WHERE user_repository_id = ? FOR UPDATE")) {
                lock.setLong(1, myRepoId);
                lock.executeQuery();

                ExecutorService other = Executors.newSingleThreadExecutor();
                try {
                    other.submit(() -> add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()))
                            .get(10, TimeUnit.SECONDS).andExpect(status().isOk());
                } finally {
                    other.shutdownNow();
                }
            } finally {
                locker.rollback();
            }
        }

        assertThat(candidateCount()).isEqualTo(2);

    }

    @Test
    @DisplayName("같은 제목·요약·커밋 묶음(순서 무관)으로 다시 보내면 새로 만들지 않고 처음 후보를 200 으로 돌려준다")
    void returnsSameCandidateForSameInput() throws Exception {

        ownCommit('a');
        ownCommit('b');

        String first = body(add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), sha('b')))
                .andExpect(status().isOk()));
        String again = body(add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('b'), sha('a'), sha('b')))
                .andExpect(status().isOk()));
        String noCommits = body(add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk()));
        String noCommitsAgain = body(add(myUserId, MY_REPO.toString(), "제목", "요약", null).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(noCommitsAgain).isEqualTo(noCommits);
        assertThat(candidateCount()).isEqualTo(2);

    }

    @Test
    @DisplayName("제목·요약·커밋 중 하나라도 다르면 새 후보를 만든다")
    void createsNewCandidateWhenAnythingDiffers() throws Exception {

        ownCommit('a');
        ownCommit('b');

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'))).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "제목 2", "요약", List.of(sha('a'))).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "제목", "요약 2", List.of(sha('a'))).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('a'), sha('b'))).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of(sha('b'))).andExpect(status().isOk());
        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());

        assertThat(candidateCount()).isEqualTo(6);

    }

    @Test
    @DisplayName("같은 글의 후보가 NEW 가 아니거나 다른 저장소 연결의 것이거나 회상으로 만든 것이면 새로 만든다")
    void createsNewCandidateWhenExistingOneDoesNotQualify() throws Exception {

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());
        jdbc.update("UPDATE candidate SET status = 'EXCLUDED'");

        add(myUserId, MY_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(2);

        add(teammateUserId, TEAMMATE_REPO.toString(), "제목", "요약", List.of()).andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE user_repository_id = ?", Long.class,
                teammateRepoId)).isEqualTo(1);

        Long recall = board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "회상 제목", null);
        jdbc.update("UPDATE candidate SET source_ref = 'RECALL', reason_text = '회상 요약' WHERE id = ?", recall);
        add(myUserId, MY_REPO.toString(), "회상 제목", "회상 요약", List.of()).andExpect(status().isOk());
        assertThat(candidateCount()).isEqualTo(5);

    }

    @Test
    @DisplayName("shas 는 1000개까지 받고 1001개는 400 이며 후보가 생기지 않는다")
    void limitsShasToMaxCommits() throws Exception {

        jdbc.update("""
                INSERT INTO git_commit (repository_id, sha, author_login, authored_at)
                SELECT ?, lpad(to_hex(n), 40, '0'), 'grow22', now() FROM generate_series(1, 1001) n
                """, repositoryId);
        List<String> shas = IntStream.rangeClosed(1, 1001).mapToObj(n -> "%040x".formatted(n)).toList();

        add(myUserId, MY_REPO.toString(), "제목", "요약", shas).andExpect(status().isBadRequest());
        assertThat(candidateCount()).isZero();

        add(myUserId, MY_REPO.toString(), "제목", "요약", shas.subList(0, 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.commits").value(1000));
        assertThat(candidateCount()).isEqualTo(1);

        add(myUserId, MY_REPO.toString(), "같은 sha", "요약", Collections.nCopies(1001, shas.get(0)))
                .andExpect(status().isOk());

    }

    private Long ownCommit(char c) {

        return board.insertCommitBy(repositoryId, sha(c), "grow22", null);

    }

    private ResultActions add(Long userId, String id, String title, String summary, List<String> shas) throws Exception {

        return mvc.perform(post("/api/repos/{id}/candidates", id)
                .with(loggedInAs(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOf(title, summary, shas)));

    }

    private String bodyOf(String title, String summary, List<String> shas) {

        Map<String, Object> body = new LinkedHashMap<>();
        if (title != null) {
            body.put("title", title);
        }
        if (summary != null) {
            body.put("summary", summary);
        }
        if (shas != null) {
            body.put("shas", shas);
        }

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

    private static String sha(char c) {

        return String.valueOf(c).repeat(40);

    }

    private long candidateCount() {

        return jdbc.queryForObject("SELECT count(*) FROM candidate", Long.class);

    }

    private List<Long> commitIdsOf(String title) {

        return jdbc.queryForList("""
                SELECT cc.commit_id FROM candidate_commit cc JOIN candidate c ON c.id = cc.candidate_id
                WHERE c.title = ? ORDER BY cc.id
                """, Long.class, title);

    }

    private String candidateText(Long candidateId) {

        return jdbc.queryForObject("SELECT to_jsonb(c)::text FROM candidate c WHERE id = ?", String.class, candidateId);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
