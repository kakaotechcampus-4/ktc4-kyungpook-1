package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubCountTarget;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpServerErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(RepositoryContributionService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryContributionServiceTest {

    private static final Instant PUSHED = Instant.parse("2026-10-05T01:00:00Z");
    private static final List<Long> GITHUB_IDS = List.of(100L, 200L);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepositoryContributionService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort github;

    private TestFixtures fixtures;
    private Long userId;
    private Long gitory;
    private Long algo;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        gitory = connect(100L, "gitory");
        algo = connect(200L, "algo");

    }

    @Test
    @DisplayName("아직 안 센 저장소를 GitHub 에 묻고 개수와 센 시각을 저장한다")
    void countsAndStoresUncountedRepositories() {

        given(github.countActivity(eq(userId), any())).willReturn(List.of(count(100L), count(200L)));

        service.refresh(userId, GITHUB_IDS);

        assertThat(askedRepositoryIds()).containsExactlyInAnyOrder(100L, 200L);
        assertThat(jdbc.queryForMap("""
                SELECT commit_count, own_commit_count, pr_count, own_pr_count, reviewed_pr_count
                FROM user_repository WHERE id = ?
                """, gitory)).isEqualTo(Map.of(
                "commit_count", 197, "own_commit_count", 52, "pr_count", 58, "own_pr_count", 23, "reviewed_pr_count", 11));
        assertThat(countedAtOf(gitory)).isNotNull();
        assertThat(countedAtOf(algo)).isNotNull();

    }

    @Test
    @DisplayName("센 뒤 새 push 가 없는 저장소는 GitHub 에 다시 묻지 않는다")
    void skipsRepositoriesWithoutNewPush() {

        markCounted(gitory, PUSHED.plusSeconds(60));
        markCounted(algo, PUSHED.plusSeconds(60));

        service.refresh(userId, GITHUB_IDS);

        verifyNoInteractions(github);

    }

    @Test
    @DisplayName("센 뒤 새 push 가 있는 저장소만 다시 센다")
    void recountsOnlyRepositoriesPushedAfterCount() {

        markCounted(gitory, PUSHED.minusSeconds(60));
        markCounted(algo, PUSHED.plusSeconds(60));
        given(github.countActivity(eq(userId), any())).willReturn(List.of(count(100L)));

        service.refresh(userId, GITHUB_IDS);

        assertThat(askedRepositoryIds()).containsExactly(100L);

    }

    @Test
    @DisplayName("GitHub 이 세지 못한 저장소는 센 시각을 남기지 않아 다음 조회 때 다시 센다")
    void leavesUncountedRepositoryForNextTime() {

        given(github.countActivity(eq(userId), any())).willReturn(List.of(count(100L)));

        service.refresh(userId, GITHUB_IDS);

        assertThat(countedAtOf(gitory)).isNotNull();
        assertThat(countedAtOf(algo)).isNull();

    }

    @Test
    @DisplayName("개수 세기가 통째로 실패해도 예외를 던지지 않고 아무것도 저장하지 않는다")
    void swallowsWholeCountFailure() {

        given(github.countActivity(anyLong(), any())).willThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertThatCode(() -> service.refresh(userId, GITHUB_IDS)).doesNotThrowAnyException();

        assertThat(countedAtOf(gitory)).isNull();
        assertThat(countedAtOf(algo)).isNull();

    }

    @Test
    @DisplayName("개수 저장을 DB 가 거절해도 예외를 밖으로 던지지 않고, 그 조회의 개수는 저장하지 않는다")
    void swallowsRejectedSave() {

        given(github.countActivity(eq(userId), any())).willReturn(List.of(
                count(100L), new GithubRepositoryCount(200L, 5, 9, 0, 0, 0)));

        assertThatCode(() -> service.refresh(userId, GITHUB_IDS)).doesNotThrowAnyException();

        assertThat(countedAtOf(gitory)).isNull();
        assertThat(countedAtOf(algo)).isNull();

    }

    @Test
    @DisplayName("센 시각은 GitHub 에 묻기 시작한 시각이라, 세는 사이에 생긴 push 는 다음 조회 때 다시 센다")
    void pushDuringCountIsCountedNextTime() {

        given(github.countActivity(eq(userId), any())).willAnswer(invocation -> {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            jdbc.update("UPDATE repository SET github_pushed_at = ? WHERE github_repo_id = 100",
                    Timestamp.from(Instant.now()));
            List<GithubCountTarget> asked = invocation.getArgument(1);
            return asked.stream().map(target -> count(target.githubRepoId())).toList();
        });

        service.refresh(userId, GITHUB_IDS);
        service.refresh(userId, GITHUB_IDS);

        ArgumentCaptor<List<GithubCountTarget>> asked = targetsCaptor();
        verify(github, times(2)).countActivity(eq(userId), asked.capture());
        assertThat(asked.getAllValues().get(1)).extracting(GithubCountTarget::githubRepoId).containsExactly(100L);

    }

    private Long connect(long githubRepoId, String name) {

        Long repositoryId = fixtures.insertRepository(githubRepoId, "grow22", name);
        jdbc.update("UPDATE repository SET github_pushed_at = ? WHERE id = ?", Timestamp.from(PUSHED), repositoryId);
        return fixtures.insertUserRepository(userId, repositoryId);

    }

    private void markCounted(Long connectionId, Instant countedAt) {

        jdbc.update("UPDATE user_repository SET counted_at = ? WHERE id = ?", Timestamp.from(countedAt), connectionId);

    }

    private Timestamp countedAtOf(Long connectionId) {

        return jdbc.queryForObject("SELECT counted_at FROM user_repository WHERE id = ?", Timestamp.class, connectionId);

    }

    private List<Long> askedRepositoryIds() {

        ArgumentCaptor<List<GithubCountTarget>> asked = targetsCaptor();
        verify(github).countActivity(eq(userId), asked.capture());
        return asked.getValue().stream().map(GithubCountTarget::githubRepoId).toList();

    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<GithubCountTarget>> targetsCaptor() {

        return ArgumentCaptor.forClass(List.class);

    }

    private static GithubRepositoryCount count(long githubRepoId) {

        return new GithubRepositoryCount(githubRepoId, 197, 52, 58, 23, 11);

    }
}
