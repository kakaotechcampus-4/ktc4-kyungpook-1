package com.gitory.backend.ingest.domain;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.gitory.backend.consent.domain.GithubTokenService;
import com.gitory.backend.ingest.infra.GithubOwnerResponse;
import com.gitory.backend.ingest.infra.GithubRepositoryClient;
import com.gitory.backend.ingest.infra.GithubRepositoryResponse;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(RepositorySyncService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositorySyncServiceTest {

    private static final String TOKEN = "gho_test";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepositorySyncService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubTokenService githubTokens;

    @MockitoBean
    GithubRepositoryClient githubClient;

    private TestFixtures fixtures;
    private Long userId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        given(githubTokens.activeTokenOf(anyLong())).willReturn(TOKEN);
    }

    @Test
    @DisplayName("처음 동기화하면 GitHub 저장소 정보가 저장되고 사용자의 저장소 목록에 추가된다")
    void savesRepositoryAndConnection() {

        githubReturns(new GithubRepositoryResponse(
                100L, "gitory", new GithubOwnerResponse("grow22"), true, null, "develop"));

        service.sync(userId);

        assertThat(jdbc.queryForMap(
                "SELECT owner_login, name, visibility, primary_language, default_branch FROM repository WHERE github_repo_id = 100"))
                .containsEntry("owner_login", "grow22")
                .containsEntry("name", "gitory")
                .containsEntry("visibility", "PRIVATE")
                .containsEntry("primary_language", null)
                .containsEntry("default_branch", "develop");
        assertThat(connectionStatusOf(userId, 100L)).isEqualTo("CONNECTED");
    }

    @Test
    @DisplayName("다시 동기화하면 저장소 정보는 최신 값으로 바뀌고 사용자의 분석 상태는 그대로 둔다")
    void updatesRepositoryButKeepsConnection() {

        githubReturns(repo(100L, "old-name"));
        service.sync(userId);
        jdbc.update("UPDATE user_repository SET status = 'ANALYZED'");

        githubReturns(repo(100L, "new-name"));
        service.sync(userId);

        assertThat(jdbc.queryForObject("SELECT name FROM repository WHERE github_repo_id = 100", String.class))
                .isEqualTo("new-name");
        assertThat(countOf("repository")).isEqualTo(1);
        assertThat(connectionStatusOf(userId, 100L)).isEqualTo("ANALYZED");
    }

    @Test
    @DisplayName("GitHub 목록에서 빠진 저장소는 지우지 않는다")
    void keepsRepositoryMissingFromGithub() {

        githubReturns(repo(100L, "kept"), repo(200L, "gone"));
        service.sync(userId);

        githubReturns(repo(100L, "kept"));
        service.sync(userId);

        assertThat(countOf("repository")).isEqualTo(2);
        assertThat(countOf("user_repository")).isEqualTo(2);
    }

    @Test
    @DisplayName("이름이 같아도 GitHub id 가 다르면 다른 저장소로 저장된다")
    void savesRecreatedRepositoryWithSameName() {

        githubReturns(repo(100L, "reborn"));
        service.sync(userId);

        githubReturns(repo(200L, "reborn"));
        service.sync(userId);

        assertThat(countOf("repository")).isEqualTo(2);
        assertThat(connectionStatusOf(userId, 200L)).isEqualTo("CONNECTED");
    }

    @Test
    @DisplayName("여러 사용자가 같은 저장소를 동기화하면 저장소는 하나만 저장되고 사용자마다 자기 목록에 추가된다")
    void sharesRepositoryAcrossUsers() {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        githubReturns(repo(100L, "team-repo"));

        service.sync(userId);
        service.sync(otherUserId);

        assertThat(countOf("repository")).isEqualTo(1);
        assertThat(countOf("user_repository")).isEqualTo(2);
    }

    @Test
    @DisplayName("저장하다 실패하면 앞에서 저장한 저장소까지 모두 되돌린다")
    void rollsBackWhenSavingFails() {

        githubReturns(repo(100L, "saved-first"), repo(200L, null));

        assertThatThrownBy(() -> service.sync(userId)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countOf("repository")).isZero();
        assertThat(countOf("user_repository")).isZero();
    }

    @Test
    @DisplayName("여러 사용자가 같은 저장소를 동시에 동기화해도 모두 성공하고 저장소는 하나만 생긴다")
    void concurrentSyncsShareOneRepository() throws Exception {

        int users = 8;
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            userIds.add(fixtures.insertUser(10L + i, "user" + i));
        }
        githubReturns(repo(100L, "team-repo"));

        ExecutorService pool = newFixedThreadPool(users);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Long id : userIds) {
            futures.add(pool.submit(() -> {
                startLine.await();
                service.sync(id);
                return null;
            }));
        }
        startLine.countDown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(countOf("repository")).isEqualTo(1);
        assertThat(countOf("user_repository")).isEqualTo(users);
    }

    private void githubReturns(GithubRepositoryResponse... repositories) {

        given(githubClient.fetchRepositories(TOKEN)).willReturn(List.of(repositories));
    }

    private static GithubRepositoryResponse repo(long githubRepoId, String name) {

        return new GithubRepositoryResponse(githubRepoId, name, new GithubOwnerResponse("grow22"), false, "Java", "main");
    }

    private String connectionStatusOf(Long userId, long githubRepoId) {

        return jdbc.queryForObject("""
                SELECT ur.status FROM user_repository ur JOIN repository r ON r.id = ur.repository_id
                WHERE ur.user_id = ? AND r.github_repo_id = ?
                """, String.class, userId, githubRepoId);
    }

    private int countOf(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
