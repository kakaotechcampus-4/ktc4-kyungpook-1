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
import java.util.Collections;
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
            {"educations":[{"level":"BACHELOR","school":"  경북대학교  ","major":"컴퓨터학부","status":"GRADUATION_EXPECTED",
                            "startMonth":"2021-03","endMonth":"2027-02","gpa":3.8,"gpaMax":4.5}],
             "careers":[{"company":"카카오","role":"","type":"INTERN","startMonth":"2025-07","endMonth":null}],
             "languages":[{"test":"OPIC","testName":"남겨도 버림","score":"IH","acquiredMonth":"2025-05"},
                          {"test":"OTHER","testName":"TOPIK","score":"6급","acquiredMonth":null}],
             "certifications":[{"name":"정보처리기사","issuer":"한국산업인력공단","acquiredMonth":"2026-06"}]}""";

    private static final String EMPTY = """
            {"educations":[],"careers":[],"languages":[],"certifications":[]}""";

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
    @DisplayName("한 번도 저장하지 않았으면 네 목록이 비고 savedAt 이 null 이다")
    void neverSavedProfileIsEmptyWithoutSavedAt() throws Exception {

        profile(myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.educations", hasSize(0)))
                .andExpect(jsonPath("$.data.careers", hasSize(0)))
                .andExpect(jsonPath("$.data.languages", hasSize(0)))
                .andExpect(jsonPath("$.data.certifications", hasSize(0)))
                .andExpect(jsonPath("$.data.savedAt").value(nullValue()));

    }

    @Test
    @DisplayName("저장하면 보낸 순서대로 남고, 글은 앞뒤 공백을 지우며 빈 글은 null, 목록에 있는 시험의 이름 칸은 버린다")
    void savesWhatWasSentAndReadsItBack() throws Exception {

        String savedAt = JsonPath.read(
                save(myUserId, FULL).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8), "$.data.savedAt");

        profile(myUserId)
                .andExpect(jsonPath("$.data.savedAt").value(savedAt))
                .andExpect(jsonPath("$.data.educations[0].level").value("BACHELOR"))
                .andExpect(jsonPath("$.data.educations[0].school").value("경북대학교"))
                .andExpect(jsonPath("$.data.educations[0].status").value("GRADUATION_EXPECTED"))
                .andExpect(jsonPath("$.data.educations[0].endMonth").value("2027-02"))
                .andExpect(jsonPath("$.data.educations[0].gpa").value(3.8))
                .andExpect(jsonPath("$.data.educations[0].gpaMax").value(4.5))
                .andExpect(jsonPath("$.data.careers[0].role").value(nullValue()))
                .andExpect(jsonPath("$.data.careers[0].endMonth").value(nullValue()))
                .andExpect(jsonPath("$.data.languages[*].test").value(contains("OPIC", "OTHER")))
                .andExpect(jsonPath("$.data.languages[0].testName").value(nullValue()))
                .andExpect(jsonPath("$.data.languages[1].testName").value("TOPIK"))
                .andExpect(jsonPath("$.data.certifications[0].name").value("정보처리기사"));

    }

    @Test
    @DisplayName("다시 저장하면 네 목록을 통째로 바꾸고, 빈 목록 네 개로 저장하면 비지만 savedAt 은 남아 처음 입력을 다시 띄우지 않는다")
    void replacesWholeProfileAndKeepsSavedAtWhenSkipped() throws Exception {

        save(myUserId, FULL).andExpect(status().isOk());
        save(myUserId, EMPTY).andExpect(status().isOk());

        profile(myUserId)
                .andExpect(jsonPath("$.data.educations", hasSize(0)))
                .andExpect(jsonPath("$.data.certifications", hasSize(0)))
                .andExpect(jsonPath("$.data.savedAt").isString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile WHERE user_id = ?", Integer.class, myUserId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("남의 인적사항은 보이지 않고 바뀌지도 않는다")
    void profilesAreSeparatedByUser() throws Exception {

        save(othersUserId, FULL).andExpect(status().isOk());

        profile(myUserId).andExpect(jsonPath("$.data.savedAt").value(nullValue()));
        save(myUserId, EMPTY).andExpect(status().isOk());
        profile(othersUserId).andExpect(jsonPath("$.data.educations", hasSize(1)));

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
                invalid("목록이 빠짐", """
                        {"educations":[],"careers":[],"languages":[]}""", "자격증 목록을 보내 주세요"),
                invalid("모르는 학력 구분", education("\"level\":\"PHD\",\"school\":\"경북대\",\"status\":\"GRADUATED\""),
                        "학력 1번째 학력 구분"),
                invalid("학교 이름 공백뿐", education("\"level\":\"BACHELOR\",\"school\":\"\\u00a0 \",\"status\":\"GRADUATED\""),
                        "학력 1번째 학교 이름을 입력해 주세요"),
                invalid("학점만 있고 만점 없음",
                        education("\"level\":\"BACHELOR\",\"school\":\"경북대\",\"status\":\"GRADUATED\",\"gpa\":3.5"),
                        "학점과 만점은 함께"),
                invalid("모르는 만점",
                        education("\"level\":\"BACHELOR\",\"school\":\"경북대\",\"status\":\"GRADUATED\",\"gpa\":3.5,\"gpaMax\":5.0"),
                        "학점 만점은"),
                invalid("만점 넘는 학점",
                        education("\"level\":\"BACHELOR\",\"school\":\"경북대\",\"status\":\"GRADUATED\",\"gpa\":4.6,\"gpaMax\":4.5"),
                        "0 부터 만점까지"),
                invalid("졸업이 입학보다 앞섬", education("\"level\":\"BACHELOR\",\"school\":\"경북대\",\"status\":\"GRADUATED\","
                        + "\"startMonth\":\"2021-03\",\"endMonth\":\"2020-02\""), "끝난 달이 시작한 달보다"),
                invalid("입사 달 없음", """
                        {"educations":[],"careers":[{"company":"카카오","type":"INTERN"}],"languages":[],"certifications":[]}""",
                        "경력 1번째 입사 달"),
                invalid("달 모양 틀림", """
                        {"educations":[],"careers":[{"company":"카카오","type":"INTERN","startMonth":"2025-7"}],"languages":[],"certifications":[]}""",
                        "YYYY-MM"),
                invalid("기타 시험 이름 없음", """
                        {"educations":[],"careers":[],"languages":[{"test":"OTHER","score":"6급"}],"certifications":[]}""",
                        "어학 1번째 시험 이름"),
                invalid("빈 항목", """
                        {"educations":[],"careers":[],"languages":[],"certifications":[null]}""", "자격증 1번째 항목이 비었습니다"),
                invalid("50자 넘는 자격증 이름", """
                        {"educations":[],"careers":[],"languages":[],"certifications":[{"name":"%s"}]}""".formatted("가".repeat(51)),
                        "50자를 넘을 수 없습니다"),
                invalid("U+0000", """
                        {"educations":[],"careers":[],"languages":[],"certifications":[{"name":"정보\\u0000처리"}]}""",
                        "U+0000"),
                invalid("자격증 21개", """
                        {"educations":[],"careers":[],"languages":[],"certifications":[%s]}""".formatted(
                                String.join(",", Collections.nCopies(21, "{\"name\":\"자격증\"}"))),
                        "20개까지"));

    }

    @Test
    @DisplayName("처음 저장이 동시에 두 번 와도 둘 다 성공하고 인적사항은 한 줄이다")
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
    @DisplayName("사용자를 지우면 인적사항도 함께 지워진다")
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

    private static String education(String fields) {

        return "{\"educations\":[{" + fields + "}],\"careers\":[],\"languages\":[],\"certifications\":[]}";

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
