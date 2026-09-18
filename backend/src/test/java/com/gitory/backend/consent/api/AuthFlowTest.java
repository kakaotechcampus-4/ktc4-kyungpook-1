package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.StubGithubConfig;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 왕복을 필터 체인 끝까지 태운다 — 시작 302 · 콜백 · 세션 · 401 · 로그아웃.
 *
 * <p>가짜는 GitHub 두 곳(토큰 교환 · 프로필 조회)뿐이다({@link StubGithubConfig}).
 * 세션은 진짜 DB 세션이고, 브라우저처럼 <b>쿠키를 들고 다니며</b> 요청한다 —
 * {@code MockHttpSession} 을 넘기는 방식은 spring-session 이 쓰이면 무시되므로
 * 세션이 실제로 이어지는지 확인하지 못한다.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Import(StubGithubConfig.class)
@Testcontainers
class AuthFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    GithubConnectionRepository connections;

    @Autowired
    TokenCipher tokenCipher;

    /** 브라우저 흉내. 응답으로 온 쿠키를 다음 요청에 그대로 실어 보낸다. */
    private final Map<String, Cookie> cookieJar = new LinkedHashMap<>();

    @BeforeEach
    void clean() {
        connections.deleteAll();
        users.deleteAll();
        cookieJar.clear();
    }

    // ───────────────────────────── 시작

    @Test
    @DisplayName("GET /api/auth/github/start 는 GitHub 인가 화면으로 보낸다 — 최소 권한만 요청한다")
    void startRedirectsToGithub() throws Exception {
        String location = perform(get("/api/auth/github/start"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://github.com/login/oauth/authorize");

        Map<String, String> query = queryOf(location);
        assertThat(query.get("client_id")).isEqualTo("test-client-id");
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost/api/auth/github/callback");
        assertThat(query.get("scope")).isEqualTo("read:user");
        assertThat(query.get("state")).isNotBlank();
    }

    // ───────────────────────────── 로그인 왕복 (완료 기준)

    @Test
    @DisplayName("로그인 → /api/me 200 → 로그아웃 → 401 왕복이 끝까지 돈다")
    void loginThenLogoutRoundTrip() throws Exception {
        login();

        // 로그인 한 번으로 사용자와 연결 동의가 생긴다.
        User saved = users.findByGithubUserId(StubGithubConfig.GITHUB_USER_ID).orElseThrow();
        assertThat(saved.getGithubLogin()).isEqualTo(StubGithubConfig.LOGIN);

        GithubConnection connection = connections.findByUserIdAndRevokedAtIsNull(saved.getId()).orElseThrow();
        assertThat(connection.scopeList()).containsExactlyInAnyOrder("read:user");
        // 저장된 값은 평문이 아니고, 복호화하면 GitHub 이 준 토큰이 나온다.
        assertThat(connection.getTokenEnc()).isNotEqualTo(StubGithubConfig.ACCESS_TOKEN);
        assertThat(tokenCipher.decrypt(connection.getTokenEnc())).isEqualTo(StubGithubConfig.ACCESS_TOKEN);
        // GitHub 이 expires_in 을 안 보냈으므로 만료 없음이다. Spring 의 issuedAt+1초
        // 자리표시가 그대로 들어가면 발급 1초 뒤 만료된 연결이 된다.
        assertThat(connection.getTokenExpiresAt()).isNull();

        // 세션 쿠키만으로 /api/me 가 열린다.
        perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login").value(StubGithubConfig.LOGIN));

        // 로그아웃은 프론트와 같은 방식으로 CSRF 를 돌려준다 — XSRF-TOKEN 쿠키를 헤더로.
        perform(post("/api/auth/logout").header("X-XSRF-TOKEN", csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));

        // 같은 쿠키를 들고 다시 와도 세션이 없다.
        perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("로그인하면 /api/me 가 프론트 계약 그대로 내려온다")
    void meMatchesTheFrontendContract() throws Exception {
        login();

        String userId = String.valueOf(users.findByGithubUserId(StubGithubConfig.GITHUB_USER_ID).orElseThrow().getId());

        perform(get("/api/me"))
                .andExpect(status().isOk())
                // 봉투 계약은 "error 키가 있고 값이 null" 이다. doesNotExist() 는 JSON null 도
                // 통과시켜 키 누락과 구분하지 못하므로 값으로 단언한다.
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.login").value(StubGithubConfig.LOGIN))
                .andExpect(jsonPath("$.data.avatarUrl").value(StubGithubConfig.AVATAR_URL))
                .andExpect(jsonPath("$.data.plan").value("FREE"))
                .andExpect(jsonPath("$.data.github.connected").value(true))
                .andExpect(jsonPath("$.data.github.scopes").isArray())
                .andExpect(jsonPath("$.data.github.connectedAt").isString())
                // 프론트 zod 가 nullable() 이라 키 자체는 반드시 있어야 한다.
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.stats.confirmedCards").value(0))
                .andExpect(jsonPath("$.data.stats.analyzedRepos").value(0))
                .andExpect(jsonPath("$.data.stats.interviewTurns").value(0))
                .andExpect(jsonPath("$.data.stats.remainingCandidates").value(0));
    }

    @Test
    @DisplayName("응답 어디에도 액세스 토큰이 실리지 않는다")
    void meNeverCarriesTheToken() throws Exception {
        login();

        String body = perform(get("/api/me")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(StubGithubConfig.ACCESS_TOKEN);
        assertThat(body).doesNotContain("token");
    }

    @Test
    @DisplayName("두 번 로그인해도 사용자와 연결이 하나씩만 남는다")
    void loggingInTwiceDoesNotDuplicate() throws Exception {
        login();
        cookieJar.clear(); // 새 브라우저처럼 처음부터 다시
        login();

        assertThat(users.count()).isEqualTo(1);
        assertThat(connections.count()).isEqualTo(1);
    }

    // ───────────────────────────── 실패 · 취소 · 미인증

    @Test
    @DisplayName("사용자가 GitHub 동의를 취소하면 /login?error=access_denied 로 돌아간다")
    void deniedConsentRedirectsToLoginWithReason() throws Exception {
        String state = startAndReadState();

        perform(get("/api/auth/github/callback")
                .param("error", "access_denied")
                .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=access_denied"));

        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("로그인하지 않은 /api 요청은 401 + UNAUTHENTICATED 다 — 로그인 페이지로 리다이렉트하지 않는다")
    void apiWithoutSessionIsUnauthorized() throws Exception {
        perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("로그인 안 된 API 호출은 세션을 만들지 않는다 — 익명 호출로 세션 테이블이 불어나지 않는다")
    void unauthenticatedCallsDoNotCreateSessions() throws Exception {
        for (int i = 0; i < 3; i++) {
            perform(get("/api/me")).andExpect(status().isUnauthorized());
        }

        assertThat(cookieJar).as("SESSION 쿠키가 나가면 서버에 세션 행이 생겼다는 뜻이다")
                .doesNotContainKey("SESSION");
    }

    @Test
    @DisplayName("CSRF 토큰 없는 로그아웃은 거부된다 — 남의 사이트가 로그아웃시킬 수 없다")
    void logoutWithoutCsrfIsRejected() throws Exception {
        login();

        perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // 여전히 로그인 상태다.
        perform(get("/api/me")).andExpect(status().isOk());
    }

    // ───────────────────────────── 브라우저 흉내

    /** 시작 → 콜백. 성공하면 계약대로 {@code /} 로 302 한다. */
    private void login() throws Exception {
        String state = startAndReadState();

        perform(get("/api/auth/github/callback")
                .param("code", "stub-authorization-code")
                .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    private String startAndReadState() throws Exception {
        String location = perform(get("/api/auth/github/start"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        return queryOf(location).get("state");
    }

    private String csrfToken() {
        Cookie cookie = cookieJar.get("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN 쿠키가 내려와야 프론트가 CSRF 헤더를 만들 수 있다").isNotNull();
        return cookie.getValue();
    }

    /** 쿠키를 실어 보내고, 응답으로 온 쿠키를 받아 적는다. */
    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request)
            throws Exception {
        if (!cookieJar.isEmpty()) {
            request = request.cookie(cookieJar.values().toArray(new Cookie[0]));
        }
        MvcResult result = mvc.perform(request).andReturn();
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookieJar.remove(cookie.getName()); // 만료 지시 = 삭제
            } else {
                cookieJar.put(cookie.getName(), cookie);
            }
        }
        return new StaticResultActions(result);
    }

    /** 이미 실행한 결과에 대해 단언만 이어 가려고 쓰는 얇은 래퍼. */
    private record StaticResultActions(MvcResult result)
            implements org.springframework.test.web.servlet.ResultActions {

        @Override
        public org.springframework.test.web.servlet.ResultActions andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            matcher.match(result);
            return this;
        }

        @Override
        public org.springframework.test.web.servlet.ResultActions andDo(
                org.springframework.test.web.servlet.ResultHandler handler) throws Exception {
            handler.handle(result);
            return this;
        }

        @Override
        public MvcResult andReturn() {
            return result;
        }
    }

    private static Map<String, String> queryOf(String url) {
        String query = URI.create(url).getQuery();
        Map<String, String> params = new LinkedHashMap<>();
        // URI#getQuery 는 퍼센트 인코딩을 이미 풀어 준다 — state 의 '=' 패딩(%3D)이 여기서 복원된다.
        // 인코딩된 채로 콜백에 실으면 저장된 인가 요청을 찾지 못해 access_denied 가 아니라
        // authorization_request_not_found 로 떨어진다.
        Arrays.stream(query.split("&")).forEach(pair -> {
            String[] parts = pair.split("=", 2);
            params.put(parts[0], parts.length > 1 ? parts[1] : "");
        });
        return params;
    }
}
