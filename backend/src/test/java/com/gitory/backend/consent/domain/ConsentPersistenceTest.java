package com.gitory.backend.consent.domain;

import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 엔티티 매핑이 V1·V2 스키마와 실제로 맞는지 본다. ddl-auto 가 validate 라
 * 컬럼 이름 하나만 어긋나도 여기서 컨텍스트가 뜨지 않는다.
 *
 * <p>특히 {@code scopes TEXT[]} 는 PostgreSQL 전용 타입이라 H2 로는 검증되지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ConsentPersistenceTest {

    private static final String[] SCOPES = {"read:user", "public_repo"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    private User persistedUser(long githubUserId, String login) {
        User user = User.register(githubUserId, login);
        em.persist(user);
        em.flush();
        return user;
    }

    @Test
    @DisplayName("사용자를 저장했다가 다시 꺼내면 가입 시각과 마지막 로그인 시각이 채워져 있다")
    void savedUserCanBeLoaded() {
        User user = persistedUser(1L, "taehun0208");
        em.clear();

        User loaded = em.find(User.class, user.getId());

        assertThat(loaded.getGithubUserId()).isEqualTo(1L);
        assertThat(loaded.getGithubLogin()).isEqualTo("taehun0208");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getLastLoginAt()).isNotNull();
        assertThat(loaded.isDeletionRequested()).isFalse();
    }

    @Test
    @DisplayName("재로그인하면 바뀐 GitHub 사용자명이 반영된다")
    void loginUpdatesChangedLogin() {
        User user = persistedUser(2L, "old-login");

        user.recordLogin("new-login");
        em.flush();
        em.clear();

        assertThat(em.find(User.class, user.getId()).getGithubLogin()).isEqualTo("new-login");
    }

    @Test
    @DisplayName("연결을 저장하면 TEXT[] 동의 범위가 순서 그대로 돌아온다")
    void scopesSurviveTheArrayColumn() {
        User user = persistedUser(3L, "grow22");

        GithubConnection connection = GithubConnection.grant(user.getId(), SCOPES, "enc:abc", null);
        em.persist(connection);
        em.flush();
        em.clear();

        GithubConnection loaded = em.find(GithubConnection.class, connection.getId());

        assertThat(loaded.scopeList()).containsExactly("read:user", "public_repo");
        assertThat(loaded.getGrantedAt()).isNotNull();
        assertThat(loaded.isActive()).isTrue();
    }

    @Test
    @DisplayName("재로그인은 새 행을 만들지 않고 기존 연결의 토큰·범위를 갈아 끼운다")
    void renewReplacesTokenAndScopes() {
        User user = persistedUser(4L, "grow22");
        GithubConnection connection = GithubConnection.grant(user.getId(), SCOPES, "enc:old", null);
        em.persist(connection);
        em.flush();

        Instant expiresAt = Instant.parse("2026-12-31T00:00:00Z");
        connection.renew(new String[]{"read:user"}, "enc:new", expiresAt);
        em.flush();
        em.clear();

        GithubConnection loaded = em.find(GithubConnection.class, connection.getId());

        assertThat(loaded.getTokenEnc()).isEqualTo("enc:new");
        assertThat(loaded.scopeList()).containsExactly("read:user");
        assertThat(loaded.getTokenExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("철회하면 토큰이 지워진다 — CHECK 제약이 요구하는 모양이다")
    void revokeClearsTheToken() {
        User user = persistedUser(5L, "grow22");
        GithubConnection connection = GithubConnection.grant(user.getId(), SCOPES, "enc:abc", null);
        em.persist(connection);
        em.flush();

        connection.revoke();
        em.flush();
        em.clear();

        GithubConnection loaded = em.find(GithubConnection.class, connection.getId());

        assertThat(loaded.getTokenEnc()).isNull();
        assertThat(loaded.getRevokedAt()).isNotNull();
        assertThat(loaded.isActive()).isFalse();
    }

    @Test
    @DisplayName("철회된 연결은 갱신할 수 없다 — 재연결은 새 동의다")
    void revokedConnectionCannotBeRenewed() {
        GithubConnection connection = GithubConnection.grant(6L, SCOPES, "enc:abc", null);
        connection.revoke();

        assertThatThrownBy(() -> connection.renew(SCOPES, "enc:new", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("한 사용자에게 활성 연결이 둘 생기지 않는다 (uq_connection_active)")
    void onlyOneActiveConnectionPerUser() {
        User user = persistedUser(7L, "grow22");
        em.persist(GithubConnection.grant(user.getId(), SCOPES, "enc:first", null));
        em.flush();

        GithubConnection second = GithubConnection.grant(user.getId(), SCOPES, "enc:second", null);

        // id 전략이 IDENTITY 라 persist 시점에 INSERT 가 바로 나간다 — flush 를 기다리지 않는다.
        // EntityManager 를 직접 쓰므로 Spring 의 예외 변환도 거치지 않아 Hibernate 원본이 올라오고,
        // 이 경로에서는 getConstraintName() 이 null 이라 메시지로 확인한다.
        assertThatThrownBy(() -> em.persist(second))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uq_connection_active");
    }
}
