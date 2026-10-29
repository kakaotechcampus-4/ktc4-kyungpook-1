package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class ManualCardApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;
    private Long myRepoId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        // 남의 저장소 연결 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 연결을 하나 더 만든다
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(2L, "grow22", "gitory-docs"));
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, repositoryId);

    }

    @Test
    @DisplayName("직접 작성을 시작하면 200 으로 빈 1번 직접 수정 버전이 현재 버전인 작성 중 정성 카드가 오고, 문장 행은 없다")
    void startsEmptyManualDraft() throws Exception {

        String body = start(myUserId, null, "로그인 세션 개선", "2024.04", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.kind").value("QUALITATIVE"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.title").value("로그인 세션 개선"))
                .andExpect(jsonPath("$.data.period").value("2024.04"))
                .andExpect(jsonPath("$.data.version.versionNo").value(1))
                .andExpect(jsonPath("$.data.version.source").value("USER_EDIT"))
                .andExpect(jsonPath("$.data.version.createdAt").isString())
                .andExpect(jsonPath("$.data.evidence", hasSize(0)))
                .andExpect(jsonPath("$.data.lowConfidenceFields", hasSize(0)))
                .andExpect(jsonPath("$.data.droppedFields", hasSize(0)))
                .andExpect(jsonPath("$.data.maskRules", hasSize(0)))
                .andExpect(jsonPath("$.data.interviewTurns").value(0))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(body, "$.data");
        assertThat(card).containsEntry("candidate", null).containsEntry("generation", null)
                .containsEntry("confirmedAt", null);
        Map<String, Object> version = JsonPath.read(body, "$.data.version");
        assertThat(version).containsEntry("situation", null).containsEntry("task", null)
                .containsEntry("action", null).containsEntry("result", null);

        String id = JsonPath.read(body, "$.data.id");
        assertThat(versionsOf(id)).containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT source FROM card_version WHERE card_id = ?", String.class,
                cardIdOf(id))).isEqualTo("USER_EDIT");
        assertThat(jdbc.queryForObject("SELECT current_version FROM card WHERE id = ?", Integer.class, cardIdOf(id)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_statement")).isZero();

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "\u00A0"})
    @DisplayName("제목이 없거나 빈 글·공백뿐(NBSP 포함)이면 400 INVALID_REQUEST 이고 카드가 생기지 않는다")
    void rejectsBlankTitle(String title) throws Exception {

        start(myUserId, null, title, "2024.04", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("제목이 256자를 넘으면 400 이고 카드가 생기지 않으며, 256자 제목은 앞뒤 공백까지 받은 글 그대로 저장된다")
    void keepsTitleUpTo256CharsAsSent() throws Exception {

        String longest = " " + "가".repeat(254) + " ";

        start(myUserId, null, "가".repeat(257), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(cardCount()).isZero();

        start(myUserId, null, longest, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(longest));
        assertThat(jdbc.queryForObject("SELECT title FROM card", String.class)).isEqualTo(longest);

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("기간이 없거나 빈 글·공백뿐이면 기간 없음(null)으로 저장된다")
    void storesBlankPeriodAsNull(String period) throws Exception {

        String body = start(myUserId, null, "카드", period, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(body, "$.data");
        assertThat(card).containsEntry("period", null);
        assertThat(jdbc.queryForObject("SELECT period FROM card", String.class)).isNull();

    }

    @Test
    @DisplayName("기간이 50자를 넘으면 400 이고 카드가 생기지 않으며, 50자 기간은 앞뒤 공백까지 받은 글 그대로 저장된다")
    void keepsPeriodUpTo50CharsAsSent() throws Exception {

        String longest = " " + "기".repeat(48) + " ";

        start(myUserId, null, "카드", "기".repeat(51), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(cardCount()).isZero();

        start(myUserId, null, "카드", longest, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(longest));
        assertThat(jdbc.queryForObject("SELECT period FROM card", String.class)).isEqualTo(longest);

    }

    @Test
    @DisplayName("제목이나 기간에 U+0000 이 있으면 400 INVALID_REQUEST 이고 카드가 생기지 않는다")
    void rejectsNulInTitleOrPeriod() throws Exception {

        start(myUserId, null, "로그인\u0000개선", "2024.04", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        start(myUserId, null, "카드", "2024\u0000.04", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("내 연결 저장소를 고르면 repo 에 공개 id·owner·name 이 실리고, 고르지 않으면 repo 는 null 이다")
    void attachesMyRepositoryOnlyWhenChosen() throws Exception {

        String withRepo = start(myUserId, null, "저장소 카드", null, MY_REPO.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repo.id").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data.repo.owner").value("grow22"))
                .andExpect(jsonPath("$.data.repo.name").value("gitory"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String withoutRepo = start(myUserId, null, "저장소 없는 카드", null, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(withoutRepo, "$.data");
        assertThat(card).containsEntry("repo", null);
        assertThat(jdbc.queryForObject("SELECT user_repository_id FROM card WHERE public_id = ?", Long.class,
                UUID.fromString(JsonPath.read(withRepo, "$.data.id")))).isEqualTo(myRepoId);

    }

    @Test
    @DisplayName("남의 저장소를 고르면 없는 저장소와 똑같이 404 NOT_FOUND 이고, 그 저장소 연결로 CREATE_CARD 거절 기록이 하나 남고 카드는 생기지 않는다")
    void deniesOthersRepository() throws Exception {

        start(myUserId, null, "카드", null, OTHERS_REPO.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", othersRepoId, "DENIED"));
        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("내 활동을 고르면 activity 에 공개 id·이름·종류가 실리고 repo 는 null 이며, 카드가 그 활동에 묶인다")
    void attachesMyActivityWhenChosen() throws Exception {

        UUID activity = UUID.randomUUID();
        Long activityId = fixtures.insertActivity(activity, myUserId, "교내 개발 동아리", "CLUB", "2025-03-01");

        String body = startIn(myUserId, null, "온보딩", null, null, activity.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("QUALITATIVE"))
                .andExpect(jsonPath("$.data.activity.id").value(activity.toString()))
                .andExpect(jsonPath("$.data.activity.name").value("교내 개발 동아리"))
                .andExpect(jsonPath("$.data.activity.type").value("CLUB"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(body, "$.data");
        assertThat(card).containsEntry("repo", null);
        assertThat(jdbc.queryForObject("SELECT activity_id FROM card WHERE public_id = ?", Long.class,
                UUID.fromString(JsonPath.read(body, "$.data.id")))).isEqualTo(activityId);

    }

    @Test
    @DisplayName("저장소와 활동을 함께 고르면 400 INVALID_REQUEST 이고 카드가 생기지 않는다")
    void rejectsRepositoryAndActivityTogether() throws Exception {

        UUID activity = UUID.randomUUID();
        fixtures.insertActivity(activity, myUserId, "동아리", "CLUB", "2025-03-01");

        startIn(myUserId, null, "카드", null, MY_REPO.toString(), activity.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("남의 활동을 고르면 없는 활동과 똑같이 404 NOT_FOUND 이고, 그 활동으로 CREATE_CARD 거절 기록이 남고 카드는 생기지 않는다")
    void deniesOthersActivity() throws Exception {

        UUID others = UUID.randomUUID();
        // 활동 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 활동을 하나 더 만든다
        fixtures.insertActivity(UUID.randomUUID(), othersUserId, "다른 동아리", "CLUB", "2025-03-01");
        Long othersActivityId = fixtures.insertActivity(others, othersUserId, "남의 동아리", "CLUB", "2025-03-01");

        startIn(myUserId, null, "카드", null, null, others.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        startIn(myUserId, null, "카드", null, null, "not-a-uuid")
                .andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", othersActivityId, "DENIED"));
        assertThat(cardCount()).isZero();

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 저장소나 UUID 가 아닌 repoId 면 404 NOT_FOUND 이고, 거절 기록도 카드도 남지 않는다")
    void unknownRepositoryIsNotFound(String repoId) throws Exception {

        start(myUserId, null, "카드", null, repoId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();
        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("같은 사용자가 같은 Idempotency-Key 로 다시 보내면 제목·기간·저장소가 달라도 새로 만들지 않고 그 키로 만든 카드의 현재 상태를 200 으로 돌려준다")
    void sameKeyReturnsCurrentStateOfFirstCard() throws Exception {

        String first = JsonPath.read(start(myUserId, KEY, "첫 제목", "2024.04", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        // 첫 응답 뒤에 저장된 칸이 다시 보낸 응답에 보이면, 첫 응답을 그대로 돌려준 것이 아니라 지금 상태를 읽은 것이다
        fixtures.insertStatement(cardIdOf(first), 1, "S", "저장된 상황", "USER_STATED", null);

        String body = start(myUserId, KEY, "다른 제목", "2025.01", MY_REPO.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.title").value("첫 제목"))
                .andExpect(jsonPath("$.data.period").value("2024.04"))
                .andExpect(jsonPath("$.data.version.situation").value("저장된 상황"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(body, "$.data");
        assertThat(card).containsEntry("repo", null);
        assertThat(cardCount()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_version")).isEqualTo(1);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingKeys")
    @DisplayName("Idempotency-Key 가 없거나 빈 글·공백뿐(NBSP 포함)이면 길이와 상관없이 키가 없는 것과 같아, 두 번 보내면 서로 걸리지 않고 카드가 두 장 생기며 둘 다 키 없이 1번 버전으로 저장된다")
    void requestsWithoutKeyCreateSeparateCards(String key) throws Exception {

        String first = JsonPath.read(start(myUserId, key, "같은 제목", null, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        String second = JsonPath.read(start(myUserId, key, "같은 제목", null, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");

        assertThat(second).isNotEqualTo(first);
        assertThat(cardCount()).isEqualTo(2);
        assertThat(versionsOf(first)).containsExactly(1);
        assertThat(versionsOf(second)).containsExactly(1);
        assertThat(count("SELECT count(*) FROM card WHERE idempotency_key IS NULL")).isEqualTo(2);

    }

    @Test
    @DisplayName("Idempotency-Key 가 255자를 넘으면 400 INVALID_REQUEST 이고 카드가 생기지 않으며, 255자 키는 받은 그대로 저장된다")
    void keepsKeyUpTo255Chars() throws Exception {

        String longest = "k".repeat(255);

        start(myUserId, "k".repeat(256), "카드", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(cardCount()).isZero();

        start(myUserId, longest, "카드", null, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM card", String.class)).isEqualTo(longest);

    }

    @Test
    @DisplayName("남의 저장소를 고르면 Idempotency-Key 가 255자를 넘어도 400 이 아니라 404 NOT_FOUND 이고 CREATE_CARD 거절 기록이 남는다")
    void checksRepositoryBeforeKeyLength() throws Exception {

        start(myUserId, "k".repeat(256), "카드", null, OTHERS_REPO.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", othersRepoId, "DENIED"));
        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 보내도 남의 저장소를 고르면 그 키로 만든 카드를 돌려주지 않고 404 NOT_FOUND 이며, CREATE_CARD 거절 기록이 하나 남고 카드는 늘지 않는다")
    void checksRepositoryBeforeSameKeyLookup() throws Exception {

        start(myUserId, KEY, "카드", null, MY_REPO.toString()).andExpect(status().isOk());

        start(myUserId, KEY, "카드", null, OTHERS_REPO.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", othersRepoId, "DENIED"));
        assertThat(cardCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("다른 사용자가 같은 Idempotency-Key 를 써도 각자 자기 카드가 생긴다")
    void sameKeyOfAnotherUserMakesSeparateCard() throws Exception {

        String mine = JsonPath.read(start(myUserId, KEY, "내 카드", null, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        String theirs = JsonPath.read(start(othersUserId, KEY, "남의 카드", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("남의 카드"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(ownerOf(mine)).isEqualTo(myUserId);
        assertThat(ownerOf(theirs)).isEqualTo(othersUserId);

    }

    @Test
    @DisplayName("카드를 만들어도 성공 기록은 남기지 않는다")
    void doesNotRecordSuccessfulStart() throws Exception {

        start(myUserId, KEY, "카드", null, MY_REPO.toString()).andExpect(status().isOk());
        start(myUserId, KEY, "카드", null, MY_REPO.toString()).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("로그인하지 않고 보내면 401 UNAUTHENTICATED 이고 카드가 생기지 않는다")
    void requiresLogin() throws Exception {

        mvc.perform(post("/api/cards/manual/draft").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("카드", null, null, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(cardCount()).isZero();

    }

    @Test
    @DisplayName("CSRF 토큰 없이 보내면 403 FORBIDDEN 이고 카드가 생기지 않는다")
    void requiresCsrfToken() throws Exception {

        mvc.perform(post("/api/cards/manual/draft").with(loggedInAs(myUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("카드", null, null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(cardCount()).isZero();

    }

    static Stream<Named<String>> missingKeys() {

        return Stream.of(
                Named.of("헤더 없음", null),
                Named.of("빈 글", ""),
                Named.of("공백뿐", "   "),
                Named.of("NBSP 뿐", "\u00A0"),
                Named.of("공백 256자", " ".repeat(256)));

    }

    private ResultActions start(Long userId, String idempotencyKey, String title, String period, String repoId)
            throws Exception {

        return startIn(userId, idempotencyKey, title, period, repoId, null);

    }

    private ResultActions startIn(Long userId, String idempotencyKey, String title, String period, String repoId,
                                  String activityId) throws Exception {

        MockHttpServletRequestBuilder request = post("/api/cards/manual/draft")
                .with(loggedInAs(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOf(title, period, repoId, activityId));

        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }

        return mvc.perform(request);

    }

    private String bodyOf(String title, String period, String repoId, String activityId) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("period", period);
        body.put("repoId", repoId);
        body.put("activityId", activityId);

        return json.writeValueAsString(body);

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private Long cardIdOf(String publicId) {

        return jdbc.queryForObject("SELECT id FROM card WHERE public_id = ?", Long.class, UUID.fromString(publicId));

    }

    private List<Integer> versionsOf(String publicId) {

        return jdbc.queryForList("SELECT version_no FROM card_version WHERE card_id = ? ORDER BY version_no",
                Integer.class, cardIdOf(publicId));

    }

    private Long ownerOf(String publicId) {

        return jdbc.queryForObject("SELECT user_id FROM card WHERE public_id = ?", Long.class,
                UUID.fromString(publicId));

    }

    private int cardCount() {

        return count("SELECT count(*) FROM card");

    }

    private int count(String sql) {

        return jdbc.queryForObject(sql, Integer.class);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
