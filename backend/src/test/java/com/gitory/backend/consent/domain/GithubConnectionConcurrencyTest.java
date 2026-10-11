package com.gitory.backend.consent.domain;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.GithubGrantClient;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * 로그인과 연결 해제가 같은 연결 행을 동시에 고쳐도 한쪽 결과가 사라지지 않는지 본다
 * 서비스 안에는 멈출 곳이 없어서, 테스트가 트랜잭션을 열고 서비스를 부른 뒤 커밋 직전에 멈춰 두 작업이 겹치는 순간을 만든다
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GithubConnectionConcurrencyTest {

    private static final long GITHUB_USER_ID = 1L;
    private static final String OLD_TOKEN = "gho_OldAccessTokenExample1234567890";
    private static final String NEW_TOKEN = "gho_NewAccessTokenExample1234567890";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    UserRepository users;

    @Autowired
    GithubConnectionRepository connections;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final TokenCipher cipher = new TokenCipher("test-encryption-key", "5c0744940b5c369b");
    private final GithubGrantClient grants = mock(GithubGrantClient.class);

    private TransactionTemplate transaction;
    private GithubLoginService loginService;
    private GithubDisconnectService disconnectService;
    private ExecutorService pool;
    private Long userId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(GITHUB_USER_ID, "grow22");
        connections.save(GithubConnection.grant(userId, new String[]{"read:user"}, cipher.encrypt(OLD_TOKEN), null));

        transaction = new TransactionTemplate(transactionManager);
        loginService = new GithubLoginService(users, connections, cipher, request -> new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("id", GITHUB_USER_ID, "login", "grow22", "avatar_url", "https://avatars/1"), "id"));
        disconnectService = new GithubDisconnectService(connections, cipher, grants, transaction);
        pool = newFixedThreadPool(2);

    }

    @AfterEach
    void tearDown() {

        pool.shutdownNow();

    }

    @Test
    @DisplayName("로그인이 연결을 읽은 뒤 끝나기 전에 해제가 오면, 해제는 로그인이 끝날 때까지 기다렸다가 로그인이 넣은 새 토큰까지 지운다")
    void disconnectWaitsForLoginInProgress() throws Exception {

        CountDownLatch loginRead = new CountDownLatch(1);
        CountDownLatch finishLogin = new CountDownLatch(1);
        Future<?> login = pool.submit(() -> transaction.executeWithoutResult(status -> {
            loginService.loadUser(loginRequest());
            loginRead.countDown();
            await(finishLogin);
        }));
        assertThat(loginRead.await(5, SECONDS)).isTrue();

        Future<?> disconnect = pool.submit(() -> disconnectService.disconnect(userId));
        assertThatThrownBy(() -> disconnect.get(500, MILLISECONDS)).isInstanceOf(TimeoutException.class);

        finishLogin.countDown();
        login.get(5, SECONDS);
        disconnect.get(5, SECONDS);

        assertThat(connections.findAll()).singleElement().satisfies(connection -> {
            assertThat(connection.getRevokedAt()).isNotNull();
            assertThat(connection.getTokenEnc()).isNull();
        });
        verify(grants).deleteGrant(NEW_TOKEN);

    }

    @Test
    @DisplayName("해제가 연결을 지운 뒤 끝나기 전에 로그인이 오면, 로그인은 해제가 끝날 때까지 기다렸다가 새 연결을 따로 만든다")
    void loginWaitsForDisconnectInProgress() throws Exception {

        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch finishDisconnect = new CountDownLatch(1);
        Future<?> disconnect = pool.submit(() -> transaction.executeWithoutResult(status -> {
            disconnectService.disconnect(userId);
            revoked.countDown();
            await(finishDisconnect);
        }));
        assertThat(revoked.await(5, SECONDS)).isTrue();

        Future<?> login = pool.submit(() -> transaction.executeWithoutResult(status -> loginService.loadUser(loginRequest())));
        assertThatThrownBy(() -> login.get(500, MILLISECONDS)).isInstanceOf(TimeoutException.class);

        finishDisconnect.countDown();
        disconnect.get(5, SECONDS);
        login.get(5, SECONDS);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM github_connection WHERE revoked_at IS NOT NULL AND token_enc IS NULL", Integer.class))
                .isOne();
        GithubConnection active = connections.findByUserIdAndRevokedAtIsNull(userId).orElseThrow();
        assertThat(cipher.decrypt(active.getTokenEnc())).isEqualTo(NEW_TOKEN);
        verify(grants).deleteGrant(OLD_TOKEN);

    }

    private static OAuth2UserRequest loginRequest() {

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
                OAuth2AccessToken.TokenType.BEARER, NEW_TOKEN, Instant.now(), null, Set.of("read:user"));

        return new OAuth2UserRequest(registration, accessToken);

    }

    private static void await(CountDownLatch latch) {

        try {
            latch.await(5, SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }

    }
}
