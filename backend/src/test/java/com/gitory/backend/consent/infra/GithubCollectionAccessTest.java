package com.gitory.backend.consent.infra;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubDisconnectService;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.GithubRateLimitedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubCountTarget;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@ExtendWith(OutputCaptureExtension.class)
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
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T00:00:00Z"));
    private final Clock clock = mock(Clock.class);
    private final GithubDisconnectService disconnects = mock(GithubDisconnectService.class);
    private final GithubRateLimitClient rateLimit = mock(GithubRateLimitClient.class);
    private final GithubCollectionAccess access = new GithubCollectionAccess(
            users, connections, cipher, null, github, counter, clock, disconnects, rateLimit, null);

    @BeforeEach
    void setUp() {

        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));
        when(clock.instant()).thenAnswer(invocation -> now.get());

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
    @DisplayName("복호화할 수 없는 토큰이면 GitHub 을 부르지 않고 예외가 난다")
    void rejectsUndecryptableToken() {

        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID))
                .thenReturn(Optional.of(GithubConnection.grant(USER_ID, SCOPES, "not-encrypted", null)));

        assertNotConnected();

    }

    @Test
    @DisplayName("쓸 수 있는 연결이면 연결 확인은 GitHub 을 부르지 않고 그냥 지나간다")
    void usableConnectionPassesCheckWithoutCallingGithub() {

        connectionExpiringAt(null);

        assertThatCode(() -> access.requireUsableConnection(USER_ID)).doesNotThrowAnyException();
        verifyNoInteractions(github, counter);

    }

    @Test
    @DisplayName("GitHub 이 토큰을 거절하면(401) 연결이 없는 것과 같은 예외가 나고, 거절받은 토큰의 암호문으로 연결 해제를 요청한다")
    void rejectedTokenIsNotConnectedAndRevokesThatToken() {

        GithubConnection connection = connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenThrow(githubRejects(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubNotConnectedException.class);
        verify(disconnects).revokeRejected(USER_ID, connection.getTokenEnc());

    }

    @Test
    @DisplayName("GitHub 이 사용 한도로 거절하면(403·429) 한도 예외가 나고 연결은 해제하지 않는다")
    void rateLimitedIsNotRevoked() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN))
                .thenThrow(githubRejects(HttpStatus.FORBIDDEN))
                .thenThrow(githubRejects(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubRateLimitedException.class);
        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubRateLimitedException.class);
        verifyNoInteractions(disconnects);

    }

    @Test
    @DisplayName("그 밖의 4xx 는 한도로 보지 않고 그대로 던지며 연결도 해제하지 않는다")
    void otherClientErrorPassesThrough() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenThrow(githubRejects(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(HttpClientErrorException.NotFound.class);
        verifyNoInteractions(disconnects);

    }

    @Test
    @DisplayName("그 밖의 GitHub 실패는 바꾸지 않고 그대로 던진다")
    void passesThroughOtherGithubFailure() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(HttpServerErrorException.class);

    }

    @Test
    @DisplayName("접근할 수 있는 저장소 뒤에 기여한 저장소를 더하고, 같은 저장소는 '/user/repos' 값을 쓰며 GitHub id 순으로 돌려준다")
    void mergesContributedRepositoriesInIdOrder() {

        connectionExpiringAt(null);
        GithubRepositoryResponse own = repository(1340154160L, "grow22", "gitory", "Java");
        GithubRepositoryResponse collaborated = repository(1147975002L, "pigmal24", "team", "Java");
        GithubRepositoryResponse organization = repository(1250219277L, "kakaotechcampus-4", "ktc4-kyungpook-1", "Java");
        when(github.fetchRepositories(TOKEN)).thenReturn(List.of(own, collaborated));
        when(github.fetchContributedRepositories(TOKEN))
                .thenReturn(List.of(organization, repository(1340154160L, "grow22", "gitory-renamed", "Kotlin")));

        assertThat(access.repositories(USER_ID)).containsExactly(collaborated, organization, own);

    }

    @ParameterizedTest
    @MethodSource("contributedFailures")
    @DisplayName("기여한 저장소 찾기가 5xx·시간 초과·401·403·429·응답 모양 이상으로 실패해도 예외 없이 접근할 수 있는 저장소만 돌려주고 연결은 해제하지 않는다")
    void keepsListedRepositoriesWhenContributedFails(RuntimeException failure) {

        connectionExpiringAt(null);
        List<GithubRepositoryResponse> listed = List.of(repository(100L, "grow22", "gitory", "Java"));
        when(github.fetchRepositories(TOKEN)).thenReturn(listed);
        when(github.fetchContributedRepositories(TOKEN)).thenThrow(failure);

        assertThat(access.repositories(USER_ID)).isEqualTo(listed);
        verifyNoInteractions(disconnects);

    }

    @Test
    @DisplayName("기여한 저장소 찾기에서 난 프로그램 오류(NPE 등)는 숨기지 않고 그대로 던진다")
    void doesNotHideProgrammingErrorsInContributed() {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenReturn(List.of());
        when(github.fetchContributedRepositories(TOKEN)).thenThrow(new NullPointerException());

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(NullPointerException.class);

    }

    @Test
    @DisplayName("기여한 저장소 찾기가 실패하면 경고에 예외 종류는 남기고 토큰과 응답 본문은 남기지 않는다")
    void logsContributedFailureWithoutTokenOrBody(CapturedOutput output) {

        connectionExpiringAt(null);
        when(github.fetchRepositories(TOKEN)).thenReturn(List.of());
        String message = "403 Forbidden: \"{\"message\": \"secret-body " + TOKEN + "\"}\"";
        when(github.fetchContributedRepositories(TOKEN)).thenThrow(new HttpClientErrorException(message,
                HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));

        access.repositories(USER_ID);

        assertThat(output.getOut() + output.getErr())
                .contains("기여한 저장소를 찾지 못해")
                .contains("사용자 " + USER_ID + ", HttpClientErrorException")
                .doesNotContain(TOKEN)
                .doesNotContain("secret-body");

    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 502})
    @DisplayName("'/user/repos' 가 401·403·429·5xx 로 실패하면 기여한 저장소는 찾지 않는다")
    void skipsContributedWhenListingFails(int statusCode) {

        connectionExpiringAt(null);
        HttpStatus status = HttpStatus.valueOf(statusCode);
        RuntimeException failure = status.is5xxServerError() ? new HttpServerErrorException(status) : githubRejects(status);
        when(github.fetchRepositories(TOKEN)).thenThrow(failure);

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(RuntimeException.class);
        verify(github, never()).fetchContributedRepositories(any());

    }

    @Test
    @DisplayName("개수를 세다가 GitHub 이 401 을 줘도 연결 해제는 요청하지 않는다")
    void countRejectionDoesNotRevoke() {

        connectionExpiringAt(null);
        when(counter.viewerId(TOKEN)).thenThrow(githubRejects(HttpStatus.UNAUTHORIZED));

        List<GithubCountTarget> targets = List.of(new GithubCountTarget(100L, "kakao", "gitory"));
        assertThatThrownBy(() -> access.countActivity(USER_ID, targets)).isInstanceOf(HttpClientErrorException.class);
        verifyNoInteractions(disconnects);

    }

    @Test
    @DisplayName("저장소를 20개씩 나눠 묻고, 한 묶음이 계속 실패해도 나머지 묶음의 결과는 돌려준다")
    void countsInBatchesAndSkipsFailedBatch() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.get(0).githubRepoId() <= 20L) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            return totalsOf(batch);
        });

        assertThat(access.countActivity(USER_ID, targets(21)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactly(21L);
        verify(counter).countCommits(eq(TOKEN), eq(VIEWER_ID), argThat((List<GithubCountTarget> batch) -> batch.size() == 20), any());

    }

    @Test
    @DisplayName("한도 거절이 아닌 이유로 묶음이 통째로 실패하면 저장소 하나씩 다시 세어 문제 저장소만 빠진다")
    void recountsFailedBatchOneByOne() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.size() > 1 || batch.get(0).githubRepoId() == 7L) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            return totalsOf(batch);
        });

        assertThat(access.countActivity(USER_ID, targets(20)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 20).filter(id -> id != 7L).boxed().toList());
        verify(counter, times(21)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("하나씩 다시 세다가 GitHub 이 한도로 거절하면 남은 저장소는 더 묻지 않는다")
    void stopsRecountingWhenRateLimited() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.size() > 1) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            if (batch.get(0).githubRepoId() == 3L) {
                throw HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "rate limited", new HttpHeaders(), new byte[0], null);
            }
            return totalsOf(batch);
        });

        assertThat(access.countActivity(USER_ID, targets(20)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactly(1L, 2L);
        verify(counter, times(4)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("저장소 20개 묶음을 3개까지 동시에 묻고, 4번째 묶음은 앞 묶음이 끝난 뒤에 묻는다")
    void asksUpToThreeBatchesAtOnce() throws Exception {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        CountDownLatch threeAsked = new CountDownLatch(3);
        CountDownLatch fourAsked = new CountDownLatch(4);
        CountDownLatch respond = new CountDownLatch(1);
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            threeAsked.countDown();
            fourAsked.countDown();
            respond.await(5, TimeUnit.SECONDS);
            return totalsOf(invocation.getArgument(2));
        });

        ExecutorService request = newSingleThreadExecutor();
        Future<List<GithubRepositoryCount>> counted = request.submit(() -> access.countActivity(USER_ID, targets(61)));

        assertThat(threeAsked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fourAsked.await(500, TimeUnit.MILLISECONDS)).isFalse();
        respond.countDown();
        assertThat(counted.get(5, TimeUnit.SECONDS))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 61).boxed().toList());
        request.shutdown();

    }

    @Test
    @DisplayName("동시에 묻는 묶음 하나가 계속 실패하면 그 묶음의 저장소만 빠지고, 나머지 묶음의 결과는 저장소 순서대로 합친다")
    void dropsOnlyFailedBatchAmongConcurrentBatches() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.get(0).githubRepoId() >= 21L && batch.get(0).githubRepoId() <= 40L) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            return totalsOf(batch);
        });

        assertThat(access.countActivity(USER_ID, targets(41)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactlyElementsOf(LongStream.concat(LongStream.rangeClosed(1, 20), LongStream.of(41)).boxed().toList());

    }

    @Test
    @DisplayName("저장소가 300개를 넘으면 앞의 15묶음(300개)만 GitHub 에 묻고 나머지는 다음 조회로 미룬다")
    void asksAtMostFifteenBatchesAtOnce() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any()))
                .thenAnswer(invocation -> totalsOf(invocation.getArgument(2)));

        assertThat(access.countActivity(USER_ID, targets(321)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 300).boxed().toList());
        verify(counter, times(15)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @ParameterizedTest
    @MethodSource("stoppingFailures")
    @DisplayName("한 묶음이 GitHub 한도로 거절되거나(403·429, 200 으로 알린 한도 초과) 토큰이 거절되면(401) 아직 묻지 않은 묶음은 묻지 않는다")
    void stopsAskingAfterRateLimited(RuntimeException failure) {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenThrow(failure);

        assertThat(access.countActivity(USER_ID, targets(200))).isEmpty();
        verify(counter, atMost(3)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    static Stream<RuntimeException> stoppingFailures() {

        return Stream.of(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "rate limited", new HttpHeaders(), new byte[0], null),
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "rate limited", new HttpHeaders(), new byte[0], null),
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate limited", new HttpHeaders(), new byte[0], null),
                new GraphqlRateLimitedException("API rate limit exceeded"));

    }

    @Test
    @DisplayName("GitHub 이 한도 초과를 200 으로 알리면 그 묶음은 하나씩 다시 세지 않고 멈춘다")
    void stopsOnRateLimitReportedWithOk() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any()))
                .thenThrow(new GraphqlRateLimitedException("API rate limit exceeded"));

        assertThat(access.countActivity(USER_ID, targets(20))).isEmpty();
        verify(counter, times(1)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("토큰이 거절되면(401) 그 묶음은 하나씩 다시 세지 않고 멈춘다")
    void stopsOnUnauthorized() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], null));

        assertThat(access.countActivity(USER_ID, targets(20))).isEmpty();
        verify(counter, times(1)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("15초가 지나면 하나씩 다시 세는 요청도 보내지 않고 그 전에 센 결과만 돌려준다")
    void stopsRecountingAfterTimeLimit() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.size() > 1) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            now.set(now.get().plusSeconds(16));
            return totalsOf(batch);
        });

        assertThat(access.countActivity(USER_ID, targets(2)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactly(1L);
        verify(counter, times(2)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("15초가 지나면 아직 묻지 않은 묶음은 보내지 않고 그 전에 센 결과만 돌려준다")
    void stopsAskingBatchesAfterTimeLimit() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        CountDownLatch threeAsked = new CountDownLatch(3);
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            threeAsked.countDown();
            threeAsked.await(5, TimeUnit.SECONDS);
            now.set(now.get().plusSeconds(16));
            return totalsOf(invocation.getArgument(2));
        });

        assertThat(access.countActivity(USER_ID, targets(61)))
                .extracting(GithubRepositoryCount::githubRepoId)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 60).boxed().toList());
        verify(counter, times(3)).countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any());

    }

    @Test
    @DisplayName("묶음에 넘기는 멈춤 확인은 15초가 지나기 전에는 거짓이고 지나면 참이다")
    void passesTimeLimitCheckToCounter() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        AtomicReference<Boolean> before = new AtomicReference<>();
        AtomicReference<Boolean> after = new AtomicReference<>();
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier mustStop = invocation.getArgument(3);
            before.set(mustStop.getAsBoolean());
            now.set(now.get().plusSeconds(16));
            after.set(mustStop.getAsBoolean());
            return totalsOf(invocation.getArgument(2));
        });

        access.countActivity(USER_ID, targets(1));

        assertThat(before.get()).isFalse();
        assertThat(after.get()).isTrue();

    }

    @Test
    @DisplayName("다른 묶음이 한도로 거절되면 이미 묻는 중인 묶음에 넘긴 멈춤 확인도 참이 된다")
    void sharesStopWithBatchesInFlight() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        AtomicReference<Boolean> stoppedInFlight = new AtomicReference<>();
        CountDownLatch inFlight = new CountDownLatch(1);
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            List<GithubCountTarget> batch = invocation.getArgument(2);
            if (batch.get(0).githubRepoId() > 20L) {
                inFlight.await(5, TimeUnit.SECONDS);
                throw HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "rate limited", new HttpHeaders(), new byte[0], null);
            }
            inFlight.countDown();
            BooleanSupplier mustStop = invocation.getArgument(3);
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!mustStop.getAsBoolean() && System.nanoTime() < giveUp) {
                Thread.onSpinWait();
            }
            stoppedInFlight.set(mustStop.getAsBoolean());
            return totalsOf(batch);
        });

        access.countActivity(USER_ID, targets(40));

        assertThat(stoppedInFlight.get()).isTrue();

    }

    @Test
    @DisplayName("내 커밋 이어 받기가 멈춰 결과에서 빠진 저장소도 마지막 로그의 건너뛴 수에 들어간다")
    void countsPagingStoppedRepositoriesAsSkipped() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier mustStop = invocation.getArgument(3);
            now.set(now.get().plusSeconds(16));
            mustStop.getAsBoolean();
            List<GithubCountTarget> batch = invocation.getArgument(2);
            return totalsOf(batch.subList(0, 1));
        });
        Logger accessLog = (Logger) LoggerFactory.getLogger(GithubCollectionAccess.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        accessLog.addAppender(logs);

        try {
            access.countActivity(USER_ID, targets(3));
        } finally {
            accessLog.detachAppender(logs);
        }

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("개수 세기에 시간이 오래 걸려 저장소 2개는 다음 조회 때 센다");

    }

    @Test
    @DisplayName("15초 전에 보낸 묶음이 늦게 끝나도 다 셌으면 다음 조회로 미룬다는 경고를 남기지 않는다")
    void doesNotWarnWhenLateBatchCountsEverything() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            now.set(now.get().plusSeconds(16));
            return totalsOf(invocation.getArgument(2));
        });
        Logger accessLog = (Logger) LoggerFactory.getLogger(GithubCollectionAccess.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        accessLog.addAppender(logs);

        List<GithubRepositoryCount> counts;
        try {
            counts = access.countActivity(USER_ID, targets(3));
        } finally {
            accessLog.detachAppender(logs);
        }

        assertThat(counts).hasSize(3);
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("다음 조회 때 센다"));

    }

    @Test
    @DisplayName("개수 세기가 끝나면 묶음을 묻던 스레드도 모두 끝난다")
    void endsBatchThreadsWhenCountingEnds() throws InterruptedException {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        Set<Thread> askers = ConcurrentHashMap.newKeySet();
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenAnswer(invocation -> {
            askers.add(Thread.currentThread());
            return totalsOf(invocation.getArgument(2));
        });

        access.countActivity(USER_ID, targets(61));

        assertThat(askers).isNotEmpty().doesNotContain(Thread.currentThread());
        for (Thread asker : askers) {
            asker.join(5000);
        }
        assertThat(askers).noneMatch(Thread::isAlive);

    }

    @Test
    @DisplayName("내 PR·리뷰한 PR 수는 저장소 이름으로 대소문자 없이 붙이고, 내 PR 이 전체 PR 보다 많으면 전체 PR 수로 맞춘다")
    void attachesPullRequestCountsByName() {

        connectionExpiringAt(null);
        searchesReturn(Map.of("kakao/gitory", 23, "grow22/algo", 9), Map.of("kakao/gitory", 11));
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenReturn(Map.of(
                100L, new GithubRepositoryTotals(197, 52, 58, 0),
                200L, new GithubRepositoryTotals(30, 30, 5, 0)));

        List<GithubRepositoryCount> counts = access.countActivity(USER_ID, List.of(
                new GithubCountTarget(100L, "Kakao", "Gitory"), new GithubCountTarget(200L, "grow22", "algo")));

        assertThat(counts).containsExactly(
                new GithubRepositoryCount(100L, 197, 52, 58, 23, 11),
                new GithubRepositoryCount(200L, 30, 30, 5, 5, 0));

    }

    @Test
    @DisplayName("GraphQL 에서 센 내 머지 커밋을 내 커밋과 전체 커밋에서 같이 뺀다")
    void subtractsOwnMergeCommits() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenReturn(Map.of(
                100L, new GithubRepositoryTotals(197, 52, 58, 9),
                200L, new GithubRepositoryTotals(10, 5, 0, 5)));

        List<GithubRepositoryCount> counts = access.countActivity(USER_ID, List.of(
                new GithubCountTarget(100L, "kakao", "gitory"), new GithubCountTarget(200L, "grow22", "algo")));

        assertThat(counts).containsExactly(
                new GithubRepositoryCount(100L, 188, 43, 58, 0, 0),
                new GithubRepositoryCount(200L, 5, 0, 0, 0, 0));

    }

    @Test
    @DisplayName("리뷰한 PR 은 내가 쓴 PR 을 빼고 검색한다")
    void searchesReviewedPullRequestsWithoutMine() {

        connectionExpiringAt(null);
        searchesReturn(Map.of(), Map.of());
        when(counter.countCommits(eq(TOKEN), eq(VIEWER_ID), any(), any())).thenReturn(Map.of());

        access.countActivity(USER_ID, List.of(new GithubCountTarget(100L, "kakao", "gitory")));

        verify(counter).countPullRequests(TOKEN, "is:pr reviewed-by:grow22 -author:grow22");

    }

    @Test
    @DisplayName("검색 결과를 다 받지 못하면 어느 저장소의 개수도 돌려주지 않고 예외를 그대로 던진다")
    void passesThroughIncompleteSearch() {

        connectionExpiringAt(null);
        when(counter.viewerId(TOKEN)).thenReturn(VIEWER_ID);
        when(counter.countPullRequests(TOKEN, "is:pr author:grow22"))
                .thenThrow(new IllegalStateException("GitHub 검색 결과를 다 받지 못했다: is:pr author:grow22"));

        List<GithubCountTarget> targets = List.of(new GithubCountTarget(100L, "kakao", "gitory"));
        assertThatThrownBy(() -> access.countActivity(USER_ID, targets)).isInstanceOf(IllegalStateException.class);

    }

    @Test
    @DisplayName("쓸 수 있는 연결이면 복호화한 토큰으로 한도를 묻고 풀리는 시각만 돌려준다")
    void asksRateLimitWithDecryptedToken() {

        connectionExpiringAt(null);
        Instant resetAt = Instant.parse("2026-10-11T03:00:00Z");
        when(rateLimit.resetAt(TOKEN)).thenReturn(Optional.of(resetAt));

        assertThat(access.rateLimitResetAt(USER_ID)).contains(resetAt);

    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_CONNECTION", "REVOKED", "EXPIRED", "DELETION_REQUESTED", "UNDECRYPTABLE"})
    @DisplayName("쓸 수 있는 연결이 없으면 GitHub 을 부르지 않고 예외 없이 빈 값을 돌려준다")
    void returnsEmptyWithoutUsableConnection(String cause) {

        switch (cause) {
            case "NO_CONNECTION" -> when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.empty());
            case "REVOKED" -> connectionExpiringAt(null).revoke();
            case "EXPIRED" -> connectionExpiringAt(Instant.now().minusSeconds(1));
            case "DELETION_REQUESTED" -> {
                User leaving = mock(User.class);
                when(leaving.isDeletionRequested()).thenReturn(true);
                when(users.findById(USER_ID)).thenReturn(Optional.of(leaving));
                connectionExpiringAt(null);
            }
            default -> when(connections.findByUserIdAndRevokedAtIsNull(USER_ID))
                    .thenReturn(Optional.of(GithubConnection.grant(USER_ID, SCOPES, "not-encrypted", null)));
        }

        assertThat(access.rateLimitResetAt(USER_ID)).isEmpty();
        verifyNoInteractions(rateLimit);

    }

    @Test
    @DisplayName("GitHub 이 401·5xx 로 답하거나 시간이 초과되면 예외 없이 빈 값을 돌려주고 로그에 토큰과 응답 본문을 남기지 않는다")
    void returnsEmptyWhenGithubFails(CapturedOutput output) {

        connectionExpiringAt(null);
        // RestClient 는 오류 메시지에 응답 본문 앞부분을 넣으므로 같은 모양으로 만든다
        String message = "401 Unauthorized: \"{\"message\": \"secret-body " + TOKEN + "\"}\"";
        byte[] body = new byte[0];
        when(rateLimit.resetAt(TOKEN))
                .thenThrow(new HttpClientErrorException(message, HttpStatus.UNAUTHORIZED, "Unauthorized",
                        new HttpHeaders(), body, StandardCharsets.UTF_8))
                .thenThrow(new HttpServerErrorException(message, HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        new HttpHeaders(), body, StandardCharsets.UTF_8))
                .thenThrow(new ResourceAccessException("Read timed out"));

        for (int i = 0; i < 3; i++) {
            assertThat(access.rateLimitResetAt(USER_ID)).isEmpty();
        }

        assertThat(output.getOut() + output.getErr())
                .contains("호출 한도를 조회하지 못했다")
                .doesNotContain(TOKEN)
                .doesNotContain("secret-body");

    }

    @Test
    @DisplayName("한도 조회가 401 이어도 연결을 해제하지 않고 그대로 둔다")
    void keepsConnectionWhenRateLimitIsRejected() {

        GithubConnection connection = connectionExpiringAt(null);
        when(rateLimit.resetAt(TOKEN)).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], null));

        assertThat(access.rateLimitResetAt(USER_ID)).isEmpty();

        assertThat(connection.isActive()).isTrue();
        verify(connections, never()).save(any());
        verifyNoInteractions(disconnects);

    }

    private static Stream<RuntimeException> contributedFailures() {

        return Stream.of(
                new HttpServerErrorException(HttpStatus.BAD_GATEWAY),
                new ResourceAccessException("Read timed out"),
                githubRejects(HttpStatus.UNAUTHORIZED),
                githubRejects(HttpStatus.FORBIDDEN),
                githubRejects(HttpStatus.TOO_MANY_REQUESTS),
                new GithubResponseShapeException("GitHub 이 기여한 저장소 목록을 주지 않았다"));

    }

    private static GithubRepositoryResponse repository(long id, String owner, String name, String language) {

        return new GithubRepositoryResponse(id, name, new GithubOwnerResponse(owner), false, language, "main", null, null);

    }

    private static List<GithubCountTarget> targets(int count) {

        return LongStream.rangeClosed(1, count)
                .mapToObj(id -> new GithubCountTarget(id, "grow22", "repo-" + id))
                .toList();

    }

    private static Map<Long, GithubRepositoryTotals> totalsOf(List<GithubCountTarget> batch) {

        return batch.stream()
                .collect(Collectors.toMap(GithubCountTarget::githubRepoId, target -> new GithubRepositoryTotals(5, 5, 0, 0)));

    }

    private void searchesReturn(Map<String, Integer> authored, Map<String, Integer> reviewed) {

        when(counter.viewerId(TOKEN)).thenReturn(VIEWER_ID);
        when(counter.countPullRequests(TOKEN, "is:pr author:grow22")).thenReturn(authored);
        when(counter.countPullRequests(TOKEN, "is:pr reviewed-by:grow22 -author:grow22")).thenReturn(reviewed);

    }

    private GithubConnection connectionExpiringAt(Instant tokenExpiresAt) {

        GithubConnection connection = GithubConnection.grant(USER_ID, SCOPES, cipher.encrypt(TOKEN), tokenExpiresAt);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        return connection;

    }

    private static HttpClientErrorException githubRejects(HttpStatus status) {

        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(), new byte[0], null);

    }

    private void assertNotConnected() {

        assertThatThrownBy(() -> access.repositories(USER_ID)).isInstanceOf(GithubNotConnectedException.class);
        assertThatThrownBy(() -> access.requireUsableConnection(USER_ID)).isInstanceOf(GithubNotConnectedException.class);
        verifyNoInteractions(github);

    }
}
