package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
class EditedCandidateCardApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private CandidateBoardFixtures board;
    private Long myUserId;
    private Long repositoryId;
    private Long myRepoId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);

    }

    @Test
    @DisplayName("후보로 카드를 만든 뒤에는 그 후보에 제외도 커밋 빼기도 409 INVALID_STATE 이고 후보와 카드가 그대로다")
    void rejectsEditsOfCandidateUsedForCard() throws Exception {

        Long candidate = cluster("로그인 개선", sha('a'), sha('b'));
        String cardId = createCard(board.publicIdOf(candidate).toString());

        for (String content : List.of(patchBody("EXCLUDED", null), patchBody(null, List.of(sha('a'))))) {
            edit(candidate, content)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data").value(nullValue()))
                    .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        }

        assertThat(statusOf(candidate)).isEqualTo("USED");
        assertThat(includedOf(candidate)).containsExactly(true, true);
        assertThat(modified(candidate)).isFalse();
        card(cardId).andExpect(jsonPath("$.data.kind").value("TECH"));

    }

    @Test
    @DisplayName("제외를 먼저 끝낸 후보로는 카드를 만들 수 없어 409 INVALID_STATE 이고 아무것도 만들지 않으며, 복원하면 다시 카드로 만들 수 있다")
    void excludedCandidateBecomesCardOnlyAfterRestore() throws Exception {

        Long candidate = cluster("로그인 개선", sha('a'));
        String publicId = board.publicIdOf(candidate).toString();
        edit(candidate, patchBody("EXCLUDED", null)).andExpect(status().isOk());

        create(publicId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(statusOf(candidate)).isEqualTo("EXCLUDED");
        assertThat(count("card")).isZero();
        assertThat(count("analysis_job")).isZero();

        edit(candidate, patchBody("NEW", null)).andExpect(status().isOk());
        createCard(publicId);

        assertThat(statusOf(candidate)).isEqualTo("USED");
        assertThat(count("card")).isEqualTo(1);

    }

    @Test
    @DisplayName("내 커밋을 붙여 직접 추가한 후보로 만든 카드는 기술 카드이고, 과제(T) 칸에만 요약이 받은 그대로 사용자 진술로 들어간다")
    void manualCandidateWithCommitsBecomesTechCardWithSummaryAsTask() throws Exception {

        board.insertCommitBy(repositoryId, sha('a'), "grow22", null);
        String summary = "  조회가 느려 인덱스를 넣었다  ";

        String cardId = createCard(add("인덱스 추가", summary, List.of(sha('a'))));

        expectOnlyTaskStatement(card(cardId), summary).andExpect(jsonPath("$.data.kind").value("TECH"));

    }

    @Test
    @DisplayName("커밋 없이 직접 추가한 후보로 만든 카드는 정성 카드이고, 과제(T) 칸에만 요약이 사용자 진술로 들어간다")
    void manualCandidateWithoutCommitsBecomesQualitativeCardWithSummaryAsTask() throws Exception {

        String summary = "매주 회의를 맡아 안건을 정리했다";

        String cardId = createCard(add("팀 회의 진행", summary, null));

        expectOnlyTaskStatement(card(cardId), summary).andExpect(jsonPath("$.data.kind").value("QUALITATIVE"));

    }

    @Test
    @DisplayName("커밋 묶음 후보에서 커밋을 모두 빼고 만든 카드는 정성 카드이고, 손댄 AI 후보의 이유 글은 과제(T) 칸에 옮기지 않는다")
    void clusterWithEveryCommitRemovedBecomesQualitativeCard() throws Exception {

        Long candidate = cluster("로그인 개선", sha('a'), sha('b'));
        edit(candidate, patchBody(null, List.of(sha('a'), sha('b')))).andExpect(status().isOk());

        String cardId = createCard(board.publicIdOf(candidate).toString());

        card(cardId)
                .andExpect(jsonPath("$.data.kind").value("QUALITATIVE"))
                .andExpect(jsonPath("$.data.version.task").value(nullValue()))
                .andExpect(jsonPath("$.data.evidence.length()").value(0));

    }

    /** 점수 있는 커밋 묶음 후보를 만들고 내 커밋마다 포함된 AI 출처 행을 sha 순서대로 잇는다 */
    private Long cluster(String title, String... shas) {

        Long candidate = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", title, "0.5");
        for (String sha : shas) {
            Long commit = board.insertCommitBy(repositoryId, sha, "grow22", null);
            board.insertCandidateCommit(candidate, commit, true, "src/" + sha.charAt(0) + ".java");
        }

        return candidate;

    }

    private ResultActions expectOnlyTaskStatement(ResultActions card, String summary) throws Exception {

        return card
                .andExpect(jsonPath("$.data.version.task").value(summary))
                .andExpect(jsonPath("$.data.version.situation").value(nullValue()))
                .andExpect(jsonPath("$.data.version.action").value(nullValue()))
                .andExpect(jsonPath("$.data.version.result").value(nullValue()))
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].field").value("T"))
                .andExpect(jsonPath("$.data.evidence[0].type").value("USER_STATED"))
                .andExpect(jsonPath("$.data.evidence[0].authoredBy").value("USER"));

    }

    private ResultActions edit(Long candidateId, String content) throws Exception {

        return mvc.perform(patch("/api/candidates/{id}", board.publicIdOf(candidateId))
                .with(loggedIn())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));

    }

    /** 값이 null 이면 그 칸을 아예 보내지 않는다 */
    private String patchBody(String statusValue, List<String> excludedShas) {

        Map<String, Object> body = new LinkedHashMap<>();
        if (statusValue != null) {
            body.put("status", statusValue);
        }
        if (excludedShas != null) {
            body.put("excludedShas", excludedShas);
        }

        return json.writeValueAsString(body);

    }

    /** 직접 추가한 후보의 공개 id 를 돌려주며, shas 가 null 이면 그 칸을 보내지 않는다 */
    private String add(String title, String summary, List<String> shas) throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("summary", summary);
        if (shas != null) {
            body.put("shas", shas);
        }

        String added = body(mvc.perform(post("/api/repos/{id}/candidates", MY_REPO)
                        .with(loggedIn())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk()));

        return JsonPath.read(added, "$.data.id");

    }

    private ResultActions create(String candidatePublicId) throws Exception {

        return mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                .with(loggedIn())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("candidateIds", List.of(candidatePublicId)))));

    }

    /** 카드 하나를 만들고 그 카드의 공개 id 를 돌려준다 */
    private String createCard(String candidatePublicId) throws Exception {

        String created = body(create(candidatePublicId).andExpect(status().isOk()));
        List<String> cardIds = JsonPath.read(created, "$.data.cardIds");
        assertThat(cardIds).hasSize(1);

        return cardIds.get(0);

    }

    private ResultActions card(String cardId) throws Exception {

        return mvc.perform(get("/api/cards/{id}", cardId).with(loggedIn())).andExpect(status().isOk());

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

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

    private int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }
}
