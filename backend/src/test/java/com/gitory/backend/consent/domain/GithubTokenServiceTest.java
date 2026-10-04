package com.gitory.backend.consent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(properties = {
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({GithubTokenService.class, TokenCipher.class})
class GithubTokenServiceTest {

    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final String[] SCOPES = {"read:user"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    GithubTokenService service;

    @Autowired
    GithubConnectionRepository connections;

    @Autowired
    TokenCipher tokenCipher;

    @Autowired
    JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {

        userId = new TestFixtures(jdbc).insertUser(1L, "grow22");
    }

    @Test
    @DisplayName("GitHub 에 연결된 사용자면 저장된 토큰을 복호화해 돌려준다")
    void returnsDecryptedToken() {

        connections.save(GithubConnection.grant(userId, SCOPES, tokenCipher.encrypt(TOKEN), null));

        assertThat(service.activeTokenOf(userId)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("GitHub 연결이 없으면 토큰을 꺼내지 않고 예외가 난다")
    void rejectsUserWithoutConnection() {

        assertThatThrownBy(() -> service.activeTokenOf(userId))
                .isInstanceOf(GithubNotConnectedException.class);
    }

    @Test
    @DisplayName("연결을 철회했으면 토큰을 꺼내지 않고 예외가 난다")
    void rejectsRevokedConnection() {

        GithubConnection connection = connections.save(
                GithubConnection.grant(userId, SCOPES, tokenCipher.encrypt(TOKEN), null));
        connection.revoke();
        connections.flush();

        assertThatThrownBy(() -> service.activeTokenOf(userId))
                .isInstanceOf(GithubNotConnectedException.class);
    }
}
