package com.gitory.backend.consent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GithubStatusTest {

    private static final String[] SCOPES = {"read:user"};

    @Test
    @DisplayName("토큰 만료 시각이 지났으면 해제하지 않은 연결이어도 연결 안 됨으로 보인다")
    void expiredTokenIsNotConnected() {

        GithubConnection expired = GithubConnection.grant(7L, SCOPES, "enc", Instant.now().minusSeconds(1));

        assertThat(GithubStatus.from(expired, null).connected()).isFalse();

    }

    @Test
    @DisplayName("만료 시각이 없거나 아직 남았으면 연결됨으로 보인다")
    void unexpiredTokenIsConnected() {

        GithubConnection noExpiry = GithubConnection.grant(7L, SCOPES, "enc", null);
        GithubConnection later = GithubConnection.grant(7L, SCOPES, "enc", Instant.now().plusSeconds(3600));

        assertThat(GithubStatus.from(noExpiry, null).connected()).isTrue();
        assertThat(GithubStatus.from(later, null).connected()).isTrue();

    }
}
