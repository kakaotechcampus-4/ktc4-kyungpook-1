package com.gitory.backend.job.infra;

import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class AssistantRepoAnalysisAdapterTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    AssistantRepoAnalysisAdapter adapter;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        fixtures.insertGithubConnection(myUserId, TOKEN);
        Long othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        fixtures.insertUserRepository(OTHERS_REPO, othersUserId, repositoryId);

    }

    @Test
    @DisplayName("내 레포의 정리를 시작하고, 이미 도는 정리가 있으면 그 Job 을 돌려준다")
    void startsAnalysisAndReturnsRunningJob() {

        UUID first = adapter.start(myUserId, MY_REPO);
        UUID second = adapter.start(myUserId, MY_REPO);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT public_id FROM analysis_job", UUID.class)).isEqualTo(first);

    }

    @Test
    @DisplayName("남의 레포는 찾을 수 없음이고, 정리를 만들지 않고 거절 기록을 남긴다")
    void othersRepoIsNotFound() {

        assertThatThrownBy(() -> adapter.start(myUserId, OTHERS_REPO)).isInstanceOf(RepoNotFoundException.class);
        assertThatThrownBy(() -> adapter.start(myUserId, UUID.randomUUID())).isInstanceOf(RepoNotFoundException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'ANALYZE' AND outcome = 'DENIED'",
                Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("쓸 수 있는 GitHub 연결이 없으면 정리를 만들지 않는다")
    void requiresUsableGithubConnection() {

        jdbc.update("DELETE FROM github_connection");

        assertThatThrownBy(() -> adapter.start(myUserId, MY_REPO)).isInstanceOf(GithubNotConnectedException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isZero();

    }

    @Test
    @DisplayName("정리 중인 레포의 공개 id 를 사용자별로 돌려준다")
    void listsAnalyzingReposPerUser() {

        adapter.start(myUserId, MY_REPO);

        assertThat(adapter.analyzingRepoIds(myUserId)).containsExactly(MY_REPO);
        assertThat(adapter.analyzingRepoIds(myUserId + 1000)).isEmpty();

    }
}
