package com.gitory.backend.consent.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubCountTarget;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

class GithubCollectionAccessTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final String[] SCOPES = {"read:user"};
    private static final String VIEWER_ID = "U_kgDOCa4RLw";

    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final TokenCipher cipher = new TokenCipher("test-encryption-key", "5c0744940b5c369b");
    private final GithubRepositoryClient github = mock(GithubRepositoryClient.class);
    private final GithubCountClient counter = mock(GithubCountClient.class);
    private final GithubCollectionAccess access = new GithubCollectionAccess(users, connections, cipher, null, github, counter);

    @BeforeEach
    void setUp() {

        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));

    }

    @Test
    @DisplayName("쓸 수 있는 연결이면 복호화한 토큰으로 GitHub 저장소 목록을 받아 온다")
    void fetchesRepositoriesWithDecryptedToken() {

        connectionExpiringAt(null);
        List<GithubRepositoryResponse> fetched = List.of(new GithubRepositoryResponse(
                100L, "gitory", new GithubOwnerResponse("grow22"), false, "Java", "main", null, null));
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

    @Test
    @DisplayName("GitHub 이 토큰을 거절하면(401) 연결이 없는 것과 같은 예외가 난다")
    void rejectedTokenIsNotConnected() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubNotConnectedException.class);

    }

    @Test
    @DisplayName("그 밖의 GitHub 실패는 바꾸지 않고 그대로 던진다")
    void passesThroughOtherGithubFailure() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(HttpServerErrorException.class);

    }

    @Test
    @DisplayName("저장소를 20개씩 나눠 묻고, 한 묶음이 실패해도 나머지 묶음의 결과는 돌려준다")
    void countsInBatchesAndSkipsFailedBatch() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        List<GithubCountTarget> targets = LongStream.rangeClosed(1, 21)
                .mapToObj(id -> new GithubCountTarget(id, "grow22", "repo-" + id))
                .toList();
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), argThat((List<GithubCountTarget> batch) -> batch.size() == 20)))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), argThat((List<GithubCountTarget> batch) -> batch.size() == 1)))
                .thenReturn(Map.of(21L, new GithubRepositoryTotals(5, 5, 0)));

        assertThat(access.countActivity(USER_ID, targets))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactly(21L);

    }

    @Test
    @DisplayName("내 PR·리뷰한 PR 수는 저장소 이름으로 대소문자 없이 붙이고, 내 PR 이 전체 PR 보다 많으면 전체 PR 수로 맞춘다")
    void attachesPullRequestCountsByName() {

        connectionExpiringAt(null);
        searchesReturn(Map.of("kakao/gitory", 23, "grow22/algo", 9), Map.of("kakao/gitory", 11));
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any())).thenReturn(Map.of(
                100L, new GithubRepositoryTotals(197, 52, 58),
                200L, new GithubRepositoryTotals(30, 30, 5)));

        List<GithubRepositoryCount> counts = access.countActivity(USER_ID, List.of(
                new GithubCountTarget(100L, "Kakao", "Gitory"), new GithubCountTarget(200L, "grow22", "algo")));

        assertThat(counts).containsExactly(
                new GithubRepositoryCount(100L, 197, 52, 58, 23, 11),
                new GithubRepositoryCount(200L, 30, 30, 5, 5, 0));

    }

    @Test
    @DisplayName("내 머지 커밋은 내 커밋과 전체 커밋에서 같이 빼고, 검색 개수가 더 커도 내 커밋이 0 밑으로 내려가지 않는다")
    void subtractsOwnMergeCommits() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countSearchedCommits(TOKEN, "author:grow22 merge:true"))
                .thenReturn(Map.of("kakao/gitory", 9, "grow22/algo", 7));
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any())).thenReturn(Map.of(
                100L, new GithubRepositoryTotals(197, 52, 58),
                200L, new GithubRepositoryTotals(10, 5, 0)));

        List<GithubRepositoryCount> counts = access.countActivity(USER_ID, List.of(
                new GithubCountTarget(100L, "kakao", "gitory"), new GithubCountTarget(200L, "grow22", "algo")));

        assertThat(counts).containsExactly(
                new GithubRepositoryCount(100L, 188, 43, 58, 0, 0),
                new GithubRepositoryCount(200L, 5, 0, 0, 0, 0));

    }

    private void searchesReturn(Map<String, Integer> authored, Map<String, Integer> reviewed) {

        when(counter.viewerId(TOKEN)).thenReturn(VIEWER_ID);
        when(counter.countPullRequests(TOKEN, "is:pr author:grow22")).thenReturn(authored);
        when(counter.countPullRequests(TOKEN, "is:pr reviewed-by:grow22")).thenReturn(reviewed);

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
