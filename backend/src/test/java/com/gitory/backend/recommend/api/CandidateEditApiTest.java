package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class CandidateEditApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TEAMMATE_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHERS_REPO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String[] CANDIDATE_KEYS = {"id", "repoId", "type", "status", "ref", "title", "reason", "meta",
            "weak", "score", "commits", "usedByCardId"};
    private static final String[] META_KEYS = {"commits", "files", "reviewComments", "days", "codeRatio"};
    private static final String NOT_FOUND_MESSAGE = "후보를 찾을 수 없습니다.";

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

        myUserId = fixtures.insertUser(1L, "grow22");
        teammateUserId = fixtures.insertUser(2L, "teammate");
        Long othersUserId = fixtures.insertUser(3L, "someone-else");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long othersRepository = fixtures.insertRepository(2L, "someone-else", "private-work");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(TEAMMATE_REPO, teammateUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, othersRepository);

    }

    @Test
    @DisplayName("로그인하지 않으면 CSRF 를 붙여도 401 이고 후보는 바뀌지 않는다")
    void rejectsAnonymousRequest() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        mvc.perform(patch("/api/candidates/{id}", board.publicIdOf(candidate)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("EXCLUDED", null)))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(candidate)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("로그인했어도 CSRF 헤더가 없으면 403 이고 후보는 바뀌지 않는다")
    void requiresCsrfToken() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        mvc.perform(patch("/api/candidates/{id}", board.publicIdOf(candidate)).with(loggedInAs(myUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("EXCLUDED", null)))
                .andExpect(status().isForbidden());

        assertThat(statusOf(candidate)).isEqualTo("NEW");
        assertThat(modified(candidate)).isFalse();

    }

    @Test
    @DisplayName("성공하면 200 이고 후보 12개 키와 meta 5개 키를 null 이어도 모두 담아 직후 보드의 그 후보 한 줄과 같게 돌려준다")
    void returnsSameLineAsBoard() throws Exception {

        Long cluster = cluster(myRepoId, "묶음", sha('a'), sha('b'));
        Long pullRequest = board.insertCandidate(myRepoId, "PR", 42, "NEW", "PR 후보", "0.9");
        Long manual = board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "직접 추가", null);

        for (Long id : List.of(cluster, pullRequest, manual)) {
            String patched = body(edit(myUserId, board.publicIdOf(id).toString(), bodyOf("EXCLUDED", null))
                    .andExpect(status().isOk()));
            String boardBody = body(mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                    .andExpect(status().isOk()));

            Map<String, Object> line = JsonPath.read(patched, "$.data");
            List<Map<String, Object>> onBoard = JsonPath.read(boardBody,
                    "$.data.candidates[?(@.id == '" + board.publicIdOf(id) + "')]");
            assertThat(line).containsOnlyKeys(CANDIDATE_KEYS);
            assertThat(JsonPath.<Map<String, Object>>read(patched, "$.data.meta")).containsOnlyKeys(META_KEYS);
            assertThat(JsonPath.<Object>read(patched, "$.error")).isNull();
            assertThat(onBoard).singleElement().isEqualTo(line);
            assertThat(line.get("status")).isEqualTo("EXCLUDED");
        }

    }

    @Test
    @DisplayName("커밋 묶음 후보의 응답은 commits 까지 담고 PR 후보의 commits 는 null 이다")
    void returnsCommitsOnlyForClusters() throws Exception {

        Long cluster = cluster(myRepoId, "묶음", sha('a'), sha('b'));
        Long pullRequest = board.insertCandidate(myRepoId, "PR", 42, "NEW", "PR 후보", "0.9");

        edit(myUserId, board.publicIdOf(cluster).toString(), bodyOf(null, List.of(sha('a'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commits.length()").value(2))
                .andExpect(jsonPath("$.data.commits[0].sha").value(sha('a')))
                .andExpect(jsonPath("$.data.commits[0].included").value(false))
                .andExpect(jsonPath("$.data.commits[1].included").value(true));
        edit(myUserId, board.publicIdOf(pullRequest).toString(), bodyOf("EXCLUDED", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commits").value(nullValue()));

    }

    @Test
    @DisplayName("repoId 는 후보가 속한 저장소 연결의 공개 id 이다")
    void returnsPublicIdOfTheConnection() throws Exception {

        Long mine = board.insertCandidate(myRepoId, "PR", 1, "NEW", "내 후보", "0.9");
        Long teammates = board.insertCandidate(teammateRepoId, "PR", 2, "NEW", "팀원 후보", "0.9");

        edit(myUserId, board.publicIdOf(mine).toString(), bodyOf("EXCLUDED", null))
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()));
        edit(teammateUserId, board.publicIdOf(teammates).toString(), bodyOf("EXCLUDED", null))
                .andExpect(jsonPath("$.data.repoId").value(TEAMMATE_REPO.toString()));

    }

    @Test
    @DisplayName("성공한 수정은 감사 기록을 남기지 않는다")
    void recordsNothingOnSuccess() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf("EXCLUDED", null)).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("GitHub 연결이 없어도 수정이 200 이다")
    void worksWithoutGithubConnection() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_connection", Long.class)).isZero();
        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf("EXCLUDED", null)).andExpect(status().isOk());

    }

    @Test
    @DisplayName("UUID 가 아닌 id 와 없는 후보는 똑같은 본문의 404 NOT_FOUND 이고 기록을 남기지 않는다")
    void answersTheSame404ForMalformedAndUnknown() throws Exception {

        String malformed = body(edit(myUserId, "not-a-uuid", bodyOf("EXCLUDED", null)).andExpect(status().isNotFound()));
        String unknown = body(edit(myUserId, UUID.randomUUID().toString(), bodyOf("EXCLUDED", null))
                .andExpect(status().isNotFound()));

        assertThat(JsonPath.<String>read(malformed, "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<String>read(malformed, "$.error.message")).isEqualTo(NOT_FOUND_MESSAGE);
        assertThat(JsonPath.<Object>read(malformed, "$.data")).isNull();
        assertThat(unknown).isEqualTo(malformed);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("남의 후보는 같은 저장소의 팀원 연결 후보도 없는 후보와 똑같은 404 이고 후보 내부 id 로 EDIT_CANDIDATE 거절 기록이 한 줄씩 남으며 후보는 그대로다")
    void hidesForeignCandidatesAndRecordsDenial() throws Exception {

        Long teammates = cluster(teammateRepoId, "팀원 묶음", sha('a'));
        Long others = cluster(othersRepoId, "남의 묶음", sha('b'));
        String unknown = body(edit(myUserId, UUID.randomUUID().toString(), bodyOf("EXCLUDED", null))
                .andExpect(status().isNotFound()));

        String teammateAnswer = body(edit(myUserId, board.publicIdOf(teammates).toString(), bodyOf("EXCLUDED", null))
                .andExpect(status().isNotFound()));
        String othersAnswer = body(edit(myUserId, board.publicIdOf(others).toString(), bodyOf(null, List.of(sha('b'))))
                .andExpect(status().isNotFound()));

        assertThat(List.of(teammateAnswer, othersAnswer)).containsOnly(unknown);
        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CANDIDATE", teammates, "DENIED"),
                new AuditRow(myUserId, "EDIT_CANDIDATE", others, "DENIED"));
        assertThat(statusOf(teammates)).isEqualTo("NEW");
        assertThat(modified(teammates)).isFalse();
        assertThat(includedOf(others)).containsExactly(true);

    }

    @Test
    @DisplayName("카드로 쓴 후보에는 제외·복원·커밋 빼기 어느 것이든 409 INVALID_STATE 이고 상태·포함 여부·손댄 표시가 그대로다")
    void rejectsEveryEditOfUsedCandidate() throws Exception {

        Long used = cluster(myRepoId, "쓴 묶음", sha('a'), sha('b'));
        jdbc.update("UPDATE candidate SET status = 'USED' WHERE id = ?", used);
        String id = board.publicIdOf(used).toString();

        for (String content : List.of(bodyOf("EXCLUDED", null), bodyOf("NEW", null), bodyOf(null, List.of(sha('a'))),
                bodyOf(null, List.of()))) {
            String answer = body(edit(myUserId, id, content).andExpect(status().isConflict()));
            assertThat(JsonPath.<String>read(answer, "$.error.code")).isEqualTo("INVALID_STATE");
            assertThat(JsonPath.<Object>read(answer, "$.data")).isNull();
        }

        assertThat(statusOf(used)).isEqualTo("USED");
        assertThat(includedOf(used)).containsExactly(true, true);
        assertThat(modified(used)).isFalse();

    }

    @Test
    @DisplayName("남의 후보에는 모르는 status 나 틀린 excludedShas 를 보내도 404 와 거절 기록이고, 카드로 쓴 내 후보에 status USED 를 보내면 400 이 아니라 409 이다")
    void checksNotFoundThenConflictThenInvalid() throws Exception {

        Long others = cluster(othersRepoId, "남의 묶음", sha('b'));
        Long used = cluster(myRepoId, "쓴 묶음", sha('a'));
        jdbc.update("UPDATE candidate SET status = 'USED' WHERE id = ?", used);
        Long mine = cluster(myRepoId, "내 묶음", sha('c'));

        edit(myUserId, board.publicIdOf(others).toString(), bodyOf("nonsense", List.of("zzz")))
                .andExpect(status().isNotFound());
        edit(myUserId, board.publicIdOf(used).toString(), bodyOf("USED", List.of("zzz")))
                .andExpect(status().isConflict());
        edit(myUserId, board.publicIdOf(mine).toString(), bodyOf("USED", null))
                .andExpect(status().isBadRequest());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CANDIDATE", others, "DENIED"));

    }

    @Test
    @DisplayName("본문이 없거나 JSON 이 아니거나 칸 모양이 틀리면 400 INVALID_REQUEST 이고 후보는 바뀌지 않는다")
    void rejectsMalformedBodies() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));
        String id = board.publicIdOf(candidate).toString();

        for (String content : List.of("", "not json", "[]", "{\"excludedShas\":\"abc\"}", "{\"excludedShas\":{}}")) {
            String answer = body(mvc.perform(patch("/api/candidates/{id}", id).with(loggedInAs(myUserId)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest()));
            assertThat(JsonPath.<String>read(answer, "$.error.code")).isEqualTo("INVALID_REQUEST");
        }

        assertThat(statusOf(candidate)).isEqualTo("NEW");
        assertThat(modified(candidate)).isFalse();

    }

    @ParameterizedTest(name = "status = \"{0}\"")
    @ValueSource(strings = {"", "excluded", "new", "Excluded", "PROPOSED", "CONFIRMED", "nonsense", " NEW"})
    @DisplayName("status 는 문자열로 받아 NEW·EXCLUDED 가 아닌 값은 소문자까지 모두 400 INVALID_REQUEST 이고 후보는 그대로다")
    void rejectsUnknownStatusValues(String value) throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        String answer = body(edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf(value, null))
                .andExpect(status().isBadRequest()));

        assertThat(JsonPath.<String>read(answer, "$.error.code")).isEqualTo("INVALID_REQUEST");
        assertThat(statusOf(candidate)).isEqualTo("NEW");
        assertThat(modified(candidate)).isFalse();

    }

    @Test
    @DisplayName("status 가 USED 이면 400 이고 후보와 손댄 표시는 그대로다")
    void rejectsUsedStatus() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf("USED", null)).andExpect(status().isBadRequest());

        assertThat(statusOf(candidate)).isEqualTo("NEW");
        assertThat(modified(candidate)).isFalse();

    }

    @Test
    @DisplayName("status 와 excludedShas 가 둘 다 없거나 null 이면 400 이고 손댄 표시도 켜지 않는다")
    void requiresStatusOrExcludedShas() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'));
        String id = board.publicIdOf(candidate).toString();

        edit(myUserId, id, "{}").andExpect(status().isBadRequest());
        edit(myUserId, id, "{\"status\":null,\"excludedShas\":null}").andExpect(status().isBadRequest());

        assertThat(modified(candidate)).isFalse();
        assertThat(statusOf(candidate)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("NEW 를 EXCLUDED 로, EXCLUDED 를 NEW 로 바꾸고 이미 그 상태면 바꾸지 않고 200 으로 지금 후보를 돌려준다")
    void switchesBetweenNewAndExcluded() throws Exception {

        Long candidate = board.insertCandidate(myRepoId, "PR", 7, "NEW", "후보", "0.5");
        String id = board.publicIdOf(candidate).toString();

        edit(myUserId, id, bodyOf("EXCLUDED", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXCLUDED"));
        assertThat(statusOf(candidate)).isEqualTo("EXCLUDED");

        edit(myUserId, id, bodyOf("EXCLUDED", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXCLUDED"));
        assertThat(statusOf(candidate)).isEqualTo("EXCLUDED");

        edit(myUserId, id, bodyOf("NEW", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEW"));
        assertThat(statusOf(candidate)).isEqualTo("NEW");

        edit(myUserId, id, bodyOf("NEW", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEW"));
        assertThat(statusOf(candidate)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("성공한 수정은 바뀐 것이 없어도 손댄 표시를 켜고 제외했다 복원해도 켜져 있다")
    void marksModifiedOnEverySuccess() throws Exception {

        Long unchanged = board.insertCandidate(myRepoId, "PR", 7, "NEW", "그대로", "0.5");
        Long restored = board.insertCandidate(myRepoId, "PR", 8, "NEW", "복원", "0.4");
        Long cluster = cluster(myRepoId, "묶음", sha('a'));

        edit(myUserId, board.publicIdOf(unchanged).toString(), bodyOf("NEW", null)).andExpect(status().isOk());
        edit(myUserId, board.publicIdOf(restored).toString(), bodyOf("EXCLUDED", null)).andExpect(status().isOk());
        edit(myUserId, board.publicIdOf(restored).toString(), bodyOf("NEW", null)).andExpect(status().isOk());
        edit(myUserId, board.publicIdOf(cluster).toString(), bodyOf(null, List.of())).andExpect(status().isOk());

        assertThat(modified(unchanged)).isTrue();
        assertThat(modified(restored)).isTrue();
        assertThat(statusOf(restored)).isEqualTo("NEW");
        assertThat(modified(cluster)).isTrue();

    }

    @Test
    @DisplayName("excludedShas 는 커밋 묶음 후보에만 받고 PR 후보와 직접 추가 후보에는 빈 목록이어도 400 이며 제외된 묶음에는 받는다")
    void acceptsExcludedShasOnlyForClusters() throws Exception {

        Long pullRequest = board.insertCandidate(myRepoId, "PR", 7, "NEW", "PR 후보", "0.5");
        Long manual = board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "직접 추가", null);
        Long excluded = cluster(myRepoId, "제외된 묶음", sha('a'));
        jdbc.update("UPDATE candidate SET status = 'EXCLUDED' WHERE id = ?", excluded);

        edit(myUserId, board.publicIdOf(pullRequest).toString(), bodyOf(null, List.of())).andExpect(status().isBadRequest());
        edit(myUserId, board.publicIdOf(manual).toString(), bodyOf("EXCLUDED", List.of(sha('a'))))
                .andExpect(status().isBadRequest());
        edit(myUserId, board.publicIdOf(excluded).toString(), bodyOf(null, List.of(sha('a')))).andExpect(status().isOk());

        assertThat(modified(pullRequest)).isFalse();
        assertThat(statusOf(manual)).isEqualTo("NEW");
        assertThat(includedOf(excluded)).containsExactly(false);

    }

    @Test
    @DisplayName("excludedShas 는 뺄 커밋 전체라서 목록의 커밋은 포함 아님으로, 나머지는 포함으로 바꾸고 행은 지우지 않으며 빈 목록이면 모두 포함으로 돌아온다")
    void replacesTheWholeSetOfRemovedCommits() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'), sha('c'));
        String id = board.publicIdOf(candidate).toString();

        edit(myUserId, id, bodyOf(null, List.of(sha('a'), sha('b')))).andExpect(status().isOk());
        assertThat(includedOf(candidate)).containsExactly(false, false, true);

        edit(myUserId, id, bodyOf(null, List.of(sha('b')))).andExpect(status().isOk());
        assertThat(includedOf(candidate)).containsExactly(true, false, true);

        edit(myUserId, id, bodyOf(null, List.of())).andExpect(status().isOk());
        assertThat(includedOf(candidate)).containsExactly(true, true, true);

    }

    @Test
    @DisplayName("sha 는 그 후보 커밋의 전체 sha 와 같아야 하고 없는 sha·짧은 sha·null·빈 글이 하나라도 있으면 400 이며 아무것도 바뀌지 않는다")
    void rejectsShasThatAreNotTheCandidatesFullShas() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'));
        Long other = cluster(myRepoId, "다른 묶음", sha('c'));
        String id = board.publicIdOf(candidate).toString();

        for (String content : List.of(
                "{\"excludedShas\":[\"" + sha('a') + "\",\"" + sha('f') + "\"]}",
                "{\"excludedShas\":[\"" + sha('c') + "\"]}",
                "{\"excludedShas\":[\"" + "a".repeat(7) + "\"]}",
                "{\"excludedShas\":[\"" + sha('a') + "\",null]}",
                "{\"excludedShas\":[\"\"]}",
                "{\"excludedShas\":[\"" + sha('a') + " \"]}",
                "{\"excludedShas\":[\"" + sha('A') + "\"]}")) {
            edit(myUserId, id, content).andExpect(status().isBadRequest());
        }

        assertThat(includedOf(candidate)).containsExactly(true, true);
        assertThat(includedOf(other)).containsExactly(true);
        assertThat(modified(candidate)).isFalse();

    }

    @Test
    @DisplayName("같은 sha 를 여러 번 보내도 한 번으로 세고, 40자보다 짧게 저장된 sha 도 보드가 준 그대로 보내면 맞는다")
    void countsRepeatedShasOnceAndMatchesShortStoredShas() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), "abc");
        String id = board.publicIdOf(candidate).toString();

        edit(myUserId, id, bodyOf(null, List.of(sha('a'), sha('a'), "abc"))).andExpect(status().isOk());

        assertThat(includedOf(candidate)).containsExactly(false, false);

    }

    @Test
    @DisplayName("모든 커밋을 빼도 200 이고 meta.commits 가 0 이다")
    void allowsExcludingEveryCommit() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'));

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf(null, List.of(sha('a'), sha('b'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.commits").value(0))
                .andExpect(jsonPath("$.data.commits.length()").value(2));

    }

    @Test
    @DisplayName("저장해 둔 meta 는 그대로 두고 meta.commits 만 포함된 커밋 수로 돌려준다")
    void keepsStoredMetaAndCountsIncludedCommits() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'), sha('c'));

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf(null, List.of(sha('b'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meta.commits").value(2))
                .andExpect(jsonPath("$.data.meta.files").value(3))
                .andExpect(jsonPath("$.data.meta.reviewComments").value(2))
                .andExpect(jsonPath("$.data.meta.days").value(5))
                .andExpect(jsonPath("$.data.meta.codeRatio").value(0.5));

    }

    @Test
    @DisplayName("status 와 excludedShas 를 함께 보내면 둘 다 검사한 뒤 함께 바꾸고 excludedShas 가 틀리면 status 도 바뀌지 않는다")
    void appliesStatusAndShasTogetherOrNotAtAll() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'));
        String id = board.publicIdOf(candidate).toString();

        edit(myUserId, id, bodyOf("EXCLUDED", List.of(sha('f')))).andExpect(status().isBadRequest());
        assertThat(statusOf(candidate)).isEqualTo("NEW");
        assertThat(includedOf(candidate)).containsExactly(true, true);
        assertThat(modified(candidate)).isFalse();

        edit(myUserId, id, bodyOf("nonsense", List.of(sha('a')))).andExpect(status().isBadRequest());
        assertThat(includedOf(candidate)).containsExactly(true, true);

        edit(myUserId, id, bodyOf("EXCLUDED", List.of(sha('a')))).andExpect(status().isOk());
        assertThat(statusOf(candidate)).isEqualTo("EXCLUDED");
        assertThat(includedOf(candidate)).containsExactly(false, true);

    }

    @Test
    @DisplayName("상태·포함 여부·손댄 표시 말고는 후보의 어떤 칸도 바꾸지 않고 후보 커밋의 출처와 표시 경로도 그대로다")
    void changesNothingElse() throws Exception {

        Long candidate = cluster(myRepoId, "묶음", sha('a'), sha('b'));
        String before = rowText(candidate);
        String commitsBefore = commitRowsText(candidate);

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf("EXCLUDED", List.of(sha('a'))))
                .andExpect(status().isOk());

        assertThat(rowText(candidate)).isEqualTo(before);
        assertThat(commitRowsText(candidate)).isEqualTo(commitsBefore);

    }

    @Test
    @DisplayName("후보를 모두 제외해도 보드는 EMPTY 가 아니라 OK 이고 제외된 후보가 목록에 남아 복원할 수 있다")
    void boardStaysOkWhenEveryCandidateIsExcluded() throws Exception {

        Long candidate = board.insertCandidate(myRepoId, "PR", 7, "NEW", "하나뿐인 후보", "0.5");

        edit(myUserId, board.publicIdOf(candidate).toString(), bodyOf("EXCLUDED", null)).andExpect(status().isOk());

        mvc.perform(get("/api/repos/{id}/candidates", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.emptyReasons.length()").value(0))
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates[0].status").value("EXCLUDED"));

    }

    /** 점수 있는 커밋 묶음 후보를 만들고 커밋마다 포함된 AI 출처 행을 sha 순서대로 이으며 숫자는 파일 3·댓글 2·5일·코드 0.5 로 정한다 */
    private Long cluster(Long connectionId, String title, String... shas) {

        Long candidate = board.insertCandidate(connectionId, "COMMIT_CLUSTER", null, "NEW", title, "0.5");
        board.setDisplay(candidate, "2026-09-23", false, 3, 2, 5, "0.5000");
        for (String sha : shas) {
            Long commit = board.insertCommitBy(repositoryId, sha, "grow22", null);
            board.insertCandidateCommit(candidate, commit, true, "src/" + sha.charAt(0) + ".java");
        }

        return candidate;

    }

    private ResultActions edit(Long userId, String id, String content) throws Exception {

        return mvc.perform(patch("/api/candidates/{id}", id)
                .with(loggedInAs(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));

    }

    /** 값이 null 이면 그 칸을 아예 보내지 않는다 */
    private String bodyOf(String statusValue, List<String> excludedShas) {

        Map<String, Object> body = new LinkedHashMap<>();
        if (statusValue != null) {
            body.put("status", statusValue);
        }
        if (excludedShas != null) {
            body.put("excludedShas", excludedShas);
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

    private String statusOf(Long candidateId) {

        return jdbc.queryForObject("SELECT status FROM candidate WHERE id = ?", String.class, candidateId);

    }

    private boolean modified(Long candidateId) {

        return jdbc.queryForObject("SELECT is_user_modified FROM candidate WHERE id = ?", Boolean.class, candidateId);

    }

    private List<Boolean> includedOf(Long candidateId) {

        return new ArrayList<>(jdbc.queryForList(
                "SELECT is_included FROM candidate_commit WHERE candidate_id = ? ORDER BY id", Boolean.class, candidateId));

    }

    /** 상태와 손댄 표시를 뺀 후보 행 전체를 글자로 비교한다 */
    private String rowText(Long candidateId) {

        return jdbc.queryForObject("SELECT (to_jsonb(c) - 'status' - 'is_user_modified')::text FROM candidate c WHERE id = ?",
                String.class, candidateId);

    }

    private String commitRowsText(Long candidateId) {

        return jdbc.queryForObject("""
                SELECT string_agg(commit_id || ':' || origin || ':' || display_path, ',' ORDER BY id)
                FROM candidate_commit WHERE candidate_id = ?
                """, String.class, candidateId);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
