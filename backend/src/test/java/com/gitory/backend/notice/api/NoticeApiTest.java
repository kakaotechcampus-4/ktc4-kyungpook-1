package com.gitory.backend.notice.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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
class NoticeApiTest {

    private static final UUID DRAFTED = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FAILED = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHERS = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID JOB = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("알림 목록은 최근 순으로 화면이 읽는 모양을 주고, 없는 값은 null 이며 cardTitles 만 빈 배열이다")
    void listsNoticesInTheShapeTheScreenReads() throws Exception {

        insert(DRAFTED, myUserId, "CARDS_DRAFTED", """
                {"repoId":"%s","repoName":"grow22/gitory","cardCount":4,"cardTitles":["가","나","다"],"jobId":"%s"}
                """.formatted(REPO, JOB), Instant.parse("2026-11-05T00:00:00Z"), null);
        insert(FAILED, myUserId, "ANALYSIS_FAILED", """
                {"repoId":"%s","repoName":"grow22/gitory","jobId":"%s","errorCode":"GITHUB_UNAUTHORIZED"}
                """.formatted(REPO, JOB), Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-06T01:00:00Z"));
        insert(OTHERS, othersUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-07T00:00:00Z"), null);

        mvc.perform(get("/api/notifications").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(FAILED.toString()))
                .andExpect(jsonPath("$.data[0].kind").value("ANALYSIS_FAILED"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-06T00:00:00Z"))
                .andExpect(jsonPath("$.data[0].readAt").value("2026-11-06T01:00:00Z"))
                .andExpect(jsonPath("$.data[0].repoId").value(REPO.toString()))
                .andExpect(jsonPath("$.data[0].repoName").value("grow22/gitory"))
                .andExpect(jsonPath("$.data[0].cardCount").value(nullValue()))
                .andExpect(jsonPath("$.data[0].cardTitles", empty()))
                .andExpect(jsonPath("$.data[0].jobId").value(JOB.toString()))
                .andExpect(jsonPath("$.data[0].errorCode").value("GITHUB_UNAUTHORIZED"))
                .andExpect(jsonPath("$.data[0].postings", empty()))
                .andExpect(jsonPath("$.data[1].id").value(DRAFTED.toString()))
                .andExpect(jsonPath("$.data[1].kind").value("CARDS_DRAFTED"))
                .andExpect(jsonPath("$.data[1].readAt").value(nullValue()))
                .andExpect(jsonPath("$.data[1].cardCount").value(4))
                .andExpect(jsonPath("$.data[1].cardTitles", contains("가", "나", "다")))
                .andExpect(jsonPath("$.data[1].errorCode").value(nullValue()));

    }

    @Test
    @DisplayName("공고 알림은 postings 에 공고 id·회사 이름·제목·채운 필수 조건 수·필수 조건 수·마감 시각을 그대로 담고 마감이 없으면 null 이다")
    void listsMatchedPostingsInTheNotice() throws Exception {

        insert(DRAFTED, myUserId, "POSTINGS_MATCHED", """
                {"postings":[
                  {"postingId":"%s","companyName":"토스","title":"백엔드 개발자","requiredMet":4,"requiredTotal":5,"expiresAt":"2026-11-20T14:59:59Z"},
                  {"postingId":"%s","companyName":"당근","title":"서버 개발자","requiredMet":1,"requiredTotal":2,"expiresAt":null}]}
                """.formatted(REPO, JOB), Instant.parse("2026-11-05T00:00:00Z"), null);

        mvc.perform(get("/api/notifications").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("POSTINGS_MATCHED"))
                .andExpect(jsonPath("$.data[0].repoId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].cardTitles", empty()))
                .andExpect(jsonPath("$.data[0].postings", hasSize(2)))
                .andExpect(jsonPath("$.data[0].postings[0].postingId").value(REPO.toString()))
                .andExpect(jsonPath("$.data[0].postings[0].companyName").value("토스"))
                .andExpect(jsonPath("$.data[0].postings[0].title").value("백엔드 개발자"))
                .andExpect(jsonPath("$.data[0].postings[0].requiredMet").value(4))
                .andExpect(jsonPath("$.data[0].postings[0].requiredTotal").value(5))
                .andExpect(jsonPath("$.data[0].postings[0].expiresAt").value("2026-11-20T14:59:59Z"))
                .andExpect(jsonPath("$.data[0].postings[1].postingId").value(JOB.toString()))
                .andExpect(jsonPath("$.data[0].postings[1].expiresAt").value(nullValue()));

    }

    @Test
    @DisplayName("자소서 카드 알림은 자소서·문항·카드의 id 와 이름을 그대로 담고, 다른 종류의 알림에는 그 칸이 null 이다")
    void listsLetterCardSuggestionInTheNotice() throws Exception {

        insert(DRAFTED, myUserId, "LETTER_CARD_SUGGESTED", """
                {"letterId":"%s","letterTitle":"가온소프트 백엔드","questionId":"%s","questionText":"문제를 해결한 경험을 쓰세요","cardId":"%s","cardTitle":"결제 장애 대응"}
                """.formatted(REPO, JOB, FAILED), Instant.parse("2026-11-05T00:00:00Z"), null);
        insert(OTHERS, myUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-04T00:00:00Z"), null);

        mvc.perform(get("/api/notifications").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("LETTER_CARD_SUGGESTED"))
                .andExpect(jsonPath("$.data[0].letterId").value(REPO.toString()))
                .andExpect(jsonPath("$.data[0].letterTitle").value("가온소프트 백엔드"))
                .andExpect(jsonPath("$.data[0].questionId").value(JOB.toString()))
                .andExpect(jsonPath("$.data[0].questionText").value("문제를 해결한 경험을 쓰세요"))
                .andExpect(jsonPath("$.data[0].cardId").value(FAILED.toString()))
                .andExpect(jsonPath("$.data[0].cardTitle").value("결제 장애 대응"))
                .andExpect(jsonPath("$.data[1].letterId").value(nullValue()))
                .andExpect(jsonPath("$.data[1].cardTitle").value(nullValue()));

    }

    @Test
    @DisplayName("알림을 읽음으로 표시하면 ok 를 주고 목록에 읽은 시각이 나타난다")
    void readMarksTheNoticeAndListShowsIt() throws Exception {

        insert(DRAFTED, myUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-05T00:00:00Z"), null);

        mvc.perform(post("/api/notifications/" + DRAFTED + "/read").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));

        mvc.perform(get("/api/notifications").with(loggedIn()))
                .andExpect(jsonPath("$.data[0].readAt").isNotEmpty());

    }

    @Test
    @DisplayName("모두 읽음은 ok 를 주고 본인의 읽지 않은 알림만 읽음이 된다")
    void readAllMarksMyNotices() throws Exception {

        insert(DRAFTED, myUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-05T00:00:00Z"), null);
        insert(OTHERS, othersUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-05T00:00:00Z"), null);

        mvc.perform(post("/api/notifications/read-all").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE read_at IS NULL", Integer.class))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("남의 알림·없는 알림·UUID 가 아닌 id 를 읽음으로 표시하려 하면 404 NOT_FOUND 이고 남의 알림은 바뀌지 않는다")
    void readHidesOthersAndMissingNotices() throws Exception {

        insert(OTHERS, othersUserId, "CARDS_DRAFTED", "{}", Instant.parse("2026-11-05T00:00:00Z"), null);

        mvc.perform(post("/api/notifications/" + OTHERS + "/read").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/api/notifications/" + UUID.randomUUID() + "/read").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/not-a-uuid/read").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE read_at IS NULL", Integer.class))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("세 API 모두 로그인이 필요하고 POST 는 CSRF 헤더도 필요하다")
    void requiresSessionAndCsrf() throws Exception {

        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/" + DRAFTED + "/read").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/" + DRAFTED + "/read").with(loggedIn())).andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/read-all").with(loggedIn())).andExpect(status().isForbidden());

    }

    private void insert(UUID publicId, Long userId, String kind, String payload, Instant createdAt, Instant readAt) {

        jdbc.update("""
                INSERT INTO notification (public_id, user_id, kind, payload, created_at, read_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, publicId, userId, kind, payload, Timestamp.from(createdAt),
                readAt == null ? null : Timestamp.from(readAt));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
