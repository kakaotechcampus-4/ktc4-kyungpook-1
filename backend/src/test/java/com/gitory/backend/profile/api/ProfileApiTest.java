package com.gitory.backend.profile.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ProfileApiTest {

    private static final String FULL = """
            {"interests":["BACKEND","DEVOPS"]}""";

    private static final String EMPTY = """
            {"interests":[]}""";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "taehun0208");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("한 번도 저장하지 않았으면 관심 분야가 비고 savedAt 이 null 이다")
    void neverSavedProfileIsEmptyWithoutSavedAt() throws Exception {

        profile(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interests", hasSize(0)))
                .andExpect(jsonPath("$.data.savedAt").value(nullValue()));

    }

    @Test
    @DisplayName("메일 주소와 수신 여부를 저장한 뒤 다시 읽으면 같은 값이 나온다")
    void mailAddressAndOptInSurviveTheDatabaseRoundTrip() throws Exception {

        save(myUserId, """
                {"interests":[],"email":" me@example.com ","mailOptIn":true}""")
                .andExpect(status().isOk());

        profile(myUserId)
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andExpect(jsonPath("$.data.mailOptIn").value(true));

    }

    @Test
    @DisplayName("관심 분야는 보낸 순서대로 남고, 다시 보내면 통째로 바뀐다")
    void savesInterestsAsSentAndReplacesThemWhole() throws Exception {

        String savedAt = JsonPath.read(
                save(myUserId, FULL).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8), "$.data.savedAt");

        profile(myUserId)
                .andExpect(jsonPath("$.data.savedAt").value(savedAt))
                .andExpect(jsonPath("$.data.interests").value(contains("BACKEND", "DEVOPS")));

        save(myUserId, interests("[\"DATA\"]")).andExpect(status().isOk());

        profile(myUserId).andExpect(jsonPath("$.data.interests").value(contains("DATA")));

    }

    @Test
    @DisplayName("빈 관심 분야로 저장하면 비지만 savedAt 은 남아 처음 입력을 다시 띄우지 않는다")
    void keepsSavedAtWhenSavedWithoutInterests() throws Exception {

        save(myUserId, FULL).andExpect(status().isOk());
        save(myUserId, EMPTY).andExpect(status().isOk());

        profile(myUserId)
                .andExpect(jsonPath("$.data.interests", hasSize(0)))
                .andExpect(jsonPath("$.data.savedAt").isString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile WHERE user_id = ?", Integer.class, myUserId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("남의 프로필은 보이지 않고 바뀌지도 않는다")
    void profilesAreSeparatedByUser() throws Exception {

        save(othersUserId, FULL).andExpect(status().isOk());

        profile(myUserId).andExpect(jsonPath("$.data.savedAt").value(nullValue()));
        save(myUserId, EMPTY).andExpect(status().isOk());
        profile(othersUserId).andExpect(jsonPath("$.data.interests", hasSize(2)));

    }

    @Test
    @DisplayName("interests 를 보내지 않으면 저장된 관심 분야를 그대로 둔다")
    void keepsStoredInterestsWhenOmitted() throws Exception {

        save(myUserId, interests("[\"BACKEND\"]")).andExpect(status().isOk());

        save(myUserId, """
                {"email":"me@example.com"}""").andExpect(status().isOk());

        profile(myUserId).andExpect(jsonPath("$.data.interests").value(contains("BACKEND")));

    }

    @Test
    @DisplayName("요청에 학력 같은 옛 항목이 와도 400 이 아니라 무시하고 응답에도 싣지 않는다")
    void ignoresRemovedSections() throws Exception {

        save(myUserId, """
                {"educations":[{"level":"BACHELOR"}],"careers":[],"languages":[],"certifications":[],"interests":["DATA"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interests").value(contains("DATA")))
                .andExpect(jsonPath("$.data.educations").doesNotExist());

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("규칙을 어기면 400 INVALID_REQUEST 로 어느 칸인지 알려 주고 아무것도 저장하지 않는다")
    void rejectsInvalidInput(String body, String message) throws Exception {

        save(myUserId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(containsString(message)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();

    }

    static Stream<Arguments> invalidBodies() {

        return Stream.of(
                invalid("관심 분야 4개", interests("[\"BACKEND\",\"FRONTEND\",\"MOBILE\",\"DEVOPS\"]"), "3개까지"),
                invalid("모르는 관심 분야", interests("[\"GAME\"]"), "관심 분야 1번째"),
                invalid("관심 분야 겹침", interests("[\"BACKEND\",\"BACKEND\"]"), "관심 분야 2번째가 앞의 것과 겹칩니다"));

    }

    private static String interests(String interests) {

        return "{\"interests\":" + interests + "}";

    }

    @Test
    @DisplayName("처음 저장이 동시에 두 번 와도 둘 다 성공하고 프로필은 한 줄이다")
    void concurrentFirstSavesKeepOneRow() throws Exception {

        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<Integer>> racing = Stream.of(FULL, EMPTY)
                .map(body -> CompletableFuture.supplyAsync(() -> {
                    await(gate);
                    try {
                        return save(myUserId, body).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .toList();
        gate.countDown();

        assertThat(racing.stream().map(CompletableFuture::join)).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile WHERE user_id = ?", Integer.class, myUserId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("사용자를 지우면 프로필도 함께 지워진다")
    void deletingUserDropsProfile() throws Exception {

        save(myUserId, FULL).andExpect(status().isOk());

        jdbc.update("DELETE FROM users WHERE id = ?", myUserId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile", Integer.class)).isZero();

    }

    @Test
    @DisplayName("로그인하지 않으면 401, CSRF 헤더 없이 저장하면 403 이다")
    void requiresLoginAndCsrf() throws Exception {

        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/profile").with(loggedInAs(myUserId))
                        .contentType(MediaType.APPLICATION_JSON).content(EMPTY))
                .andExpect(status().isForbidden());

    }

    private static Arguments invalid(String name, String body, String message) {

        return Arguments.of(Named.of(name, body), message);

    }

    private ResultActions profile(Long userId) throws Exception {

        return mvc.perform(get("/api/profile").with(loggedInAs(userId)));

    }

    private ResultActions save(Long userId, String body) throws Exception {

        return mvc.perform(put("/api/profile").with(loggedInAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private static void await(CountDownLatch latch) {

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("기다리던 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "taehun0208", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
