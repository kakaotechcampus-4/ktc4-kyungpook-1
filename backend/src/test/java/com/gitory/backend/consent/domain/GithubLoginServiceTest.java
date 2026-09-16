package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그인 한 번이 users · github_connection 에 무엇을 남기는지 본다.
 *
 * <p>GitHub 은 부르지 않는다 — 프로필 조회를 이음매로 갈아 끼우고 DB 만 진짜를 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GithubLoginServiceTest {

    private static final String PLAIN_TOKEN = "gho_PlainAccessTokenExample1234567890";
    private static final Instant ISSUED_AT = Instant.parse("2026-09-16T02:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    UserRepository users;

    @Autowired
    GithubConnectionRepository connections;

    private final TokenCipher tokenCipher = new TokenCipher("test-encryption-key", "5c0744940b5c369b");

    private GithubLoginService loginWithProfile(Map<String, Object> profile) {
        OAuth2User githubUser = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), profile, "id");
        return new GithubLoginService(users, connections, tokenCipher, request -> githubUser);
    }

    private static Map<String, Object> profile(long id, String login, String avatarUrl) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", id);
        attributes.put("login", login);
        attributes.put("avatar_url", avatarUrl);
        return attributes;
    }

    /** GitHub 은 scope 를 쉼표로 붙여 내려준다 — Spring 은 공백으로만 끊으므로 한 덩어리로 들어온다. */
    private static OAuth2UserRequest request(String rawScope) {
        return request(rawScope, null);
    }

    private static OAuth2UserRequest request(String rawScope, Instant expiresAt) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8080/api/auth/github/callback")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .build();

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, PLAIN_TOKEN, ISSUED_AT, expiresAt, Set.of(rawScope));

        return new OAuth2UserRequest(registration, accessToken);
    }

    @BeforeEach
    void clean() {
        connections.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("첫 로그인이면 사용자와 연결 동의가 새로 생긴다")
    void firstLoginCreatesUserAndConnection() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", "https://avatars/1"));

        OAuth2User principal = service.loadUser(request("public_repo,read:user"));

        assertThat(users.count()).isEqualTo(1);
        assertThat(connections.count()).isEqualTo(1);

        User saved = users.findByGithubUserId(4242L).orElseThrow();
        assertThat(saved.getGithubLogin()).isEqualTo("taehun0208");
        assertThat(saved.getLastLoginAt()).isNotNull();

        assertThat(principal).isInstanceOf(LoginUser.class);
        LoginUser loginUser = (LoginUser) principal;
        assertThat(loginUser.id()).isEqualTo(saved.getId());
        assertThat(loginUser.login()).isEqualTo("taehun0208");
        assertThat(loginUser.avatarUrl()).isEqualTo("https://avatars/1");
        assertThat(loginUser.getName()).isEqualTo(String.valueOf(saved.getId()));
    }

    @Test
    @DisplayName("저장된 토큰은 평문이 아니고, 복호화하면 원래 토큰이 나온다")
    void tokenIsStoredEncrypted() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", null));

        service.loadUser(request("public_repo,read:user"));

        GithubConnection connection = connections.findAll().getFirst();
        assertThat(connection.getTokenEnc()).isNotEqualTo(PLAIN_TOKEN);
        assertThat(tokenCipher.decrypt(connection.getTokenEnc())).isEqualTo(PLAIN_TOKEN);
    }

    @Test
    @DisplayName("GitHub 이 쉼표로 붙여 보낸 동의 범위가 스코프 두 개로 쪼개져 저장된다")
    void commaSeparatedScopesAreSplit() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", null));

        service.loadUser(request("public_repo,read:user"));

        assertThat(connections.findAll().getFirst().scopeList())
                .containsExactlyInAnyOrder("public_repo", "read:user");
    }

    @Test
    @DisplayName("만료 없는 토큰은 만료 시각이 비어 저장된다 — Spring 의 issuedAt+1초 자리표시를 그대로 믿지 않는다")
    void placeholderExpiryIsStoredAsNull() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", null));

        // GitHub OAuth App 의 기본 토큰은 expires_in 이 없고, 그때 Spring 은 만료를
        // issuedAt + 1초로 채운다. 이 값을 그대로 저장하면 발급 1초 뒤 만료된 연결이 된다.
        service.loadUser(request("read:user", ISSUED_AT.plusSeconds(1)));

        assertThat(connections.findAll().getFirst().getTokenExpiresAt()).isNull();
    }

    @Test
    @DisplayName("진짜 만료가 있는 토큰은 만료 시각을 그대로 저장한다")
    void realExpiryIsKept() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", null));

        // Expire user access tokens 를 켠 앱은 8시간짜리 토큰을 준다.
        Instant expiresAt = ISSUED_AT.plusSeconds(8 * 3600);
        service.loadUser(request("read:user", expiresAt));

        assertThat(connections.findAll().getFirst().getTokenExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("재로그인해도 행이 늘지 않고, 바뀐 사용자명과 새 토큰이 반영된다")
    void secondLoginUpdatesInPlace() {
        loginWithProfile(profile(4242L, "old-login", null)).loadUser(request("public_repo,read:user"));
        String firstTokenEnc = connections.findAll().getFirst().getTokenEnc();

        loginWithProfile(profile(4242L, "new-login", null)).loadUser(request("read:user"));

        assertThat(users.count()).isEqualTo(1);
        assertThat(connections.count()).isEqualTo(1);
        assertThat(users.findByGithubUserId(4242L).orElseThrow().getGithubLogin()).isEqualTo("new-login");

        GithubConnection connection = connections.findAll().getFirst();
        assertThat(connection.scopeList()).containsExactly("read:user");
        // 같은 평문 토큰이어도 암호문은 매번 달라진다 — 갱신이 실제로 일어났는지는 복호화로 본다.
        assertThat(connection.getTokenEnc()).isNotEqualTo(firstTokenEnc);
        assertThat(tokenCipher.decrypt(connection.getTokenEnc())).isEqualTo(PLAIN_TOKEN);
    }

    @Test
    @DisplayName("다른 GitHub 계정으로 로그인하면 별개의 사용자가 된다")
    void differentGithubAccountIsADifferentUser() {
        loginWithProfile(profile(1L, "first", null)).loadUser(request("read:user"));
        loginWithProfile(profile(2L, "second", null)).loadUser(request("read:user"));

        assertThat(users.count()).isEqualTo(2);
        assertThat(connections.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("동의 범위가 비면 연결을 만들지 않고 로그인을 실패시킨다")
    void emptyScopeFailsLogin() {
        GithubLoginService service = loginWithProfile(profile(4242L, "taehun0208", null));

        assertThatThrownBy(() -> service.loadUser(request(" ")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("GitHub 프로필에 login 이 없으면 로그인을 실패시킨다")
    void missingLoginFailsLogin() {
        GithubLoginService service = loginWithProfile(profile(4242L, null, null));

        assertThatThrownBy(() -> service.loadUser(request("read:user")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}
