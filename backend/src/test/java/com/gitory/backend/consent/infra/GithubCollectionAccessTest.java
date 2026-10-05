package com.gitory.backend.consent.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class GithubCollectionAccessTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final String[] SCOPES = {"read:user"};

    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final TokenCipher cipher = new TokenCipher("test-encryption-key", "5c0744940b5c369b");
    private final GithubRepositoryClient github = mock(GithubRepositoryClient.class);
    private final GithubCollectionAccess access = new GithubCollectionAccess(users, connections, cipher, null, github);

    @BeforeEach
    void setUp() {

        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));

    }

    @Test
    @DisplayName("쓸 수 있는 연결이면 복호화한 토큰으로 GitHub 저장소 목록을 받아 온다")
    void fetchesRepositoriesWithDecryptedToken() {

        connectionExpiringAt(null);
        List<GithubRepositoryResponse> fetched = List.of(new GithubRepositoryResponse(
                100L, "gitory", new GithubOwnerResponse("grow22"), false, "Java", "main"));
        when(github.fetchRepositories(TOKEN)).thenReturn(fetched);

        assertThat(access.repositories(USER_ID)).isEqualTo(fetched);

    }

    @Test
    @DisplayName("GitHub 연결이 없으면 GitHub 을 부르지 않고 예외가 난다")
    void rejectsUserWithoutConnection() {

        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertNotConnected();

    }

    @Test
    @DisplayName("연결을 철회했으면 GitHub 을 부르지 않고 예외가 난다")
    void rejectsRevokedConnection() {

        connectionExpiringAt(null).revoke();

        assertNotConnected();

    }

    @Test
    @DisplayName("토큰이 만료됐으면 GitHub 을 부르지 않고 예외가 난다")
    void rejectsExpiredConnection() {

        connectionExpiringAt(Instant.now().minusSeconds(1));

        assertNotConnected();

    }

    @Test
    @DisplayName("탈퇴를 요청한 사용자면 GitHub 을 부르지 않고 예외가 난다")
    void rejectsUserRequestedDeletion() {

        User leaving = mock(User.class);
        when(leaving.isDeletionRequested()).thenReturn(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(leaving));
        connectionExpiringAt(null);

        assertNotConnected();

    }

    private GithubConnection connectionExpiringAt(Instant tokenExpiresAt) {

        GithubConnection connection = GithubConnection.grant(USER_ID, SCOPES, cipher.encrypt(TOKEN), tokenExpiresAt);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        return connection;

    }

    private void assertNotConnected() {

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubNotConnectedException.class);
        verifyNoInteractions(github);

    }
}
