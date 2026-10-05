package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.ingest.infra.CollectionRunRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.IngestRequest;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ActivityStoreService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityStoreServiceTest {

    private static final String SHA_1 = "1111111111111111111111111111111111111111";
    private static final String SHA_2 = "2222222222222222222222222222222222222222";
    private static final List<String> BRANCHES = List.of("develop", "feature/session-index");
    private static final Instant SINCE = Instant.parse("2026-09-28T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ActivityStoreService service;

    @Autowired
    CollectionRunRepository runs;

    @Autowired
    GitCommitRepository commits;

    @Autowired
    JdbcTemplate jdbc;

    private Long userRepositoryId;
    private Long repositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        Long userId = fixtures.insertUser(1L, "grow22");

        // 저장소를 둘 만들어 user_repository.id 와 repository.id 를 다르게 한다.
        // 두 번호가 같으면 엉뚱한 번호를 넣어도 테스트가 통과한다
        fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");

        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
    }

    @Test
    @DisplayName("수집 이력과 커밋의 필드마다 맞는 값이 저장된다")
    void savesRunAndCommit() {

        Long runId = service.store(request(), new CollectedActivity(List.of(commit(SHA_1)),
                List.of(new CollectedPullRequest(17)), List.of(new CollectedIssue(42)),
                PartialReason.CAP_EXCEEDED));

        CollectionRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getBranches()).containsExactly("develop", "feature/session-index");
        assertThat(run.getSince()).isEqualTo(SINCE);
        assertThat(run.getHeadSha()).isNull();
        assertThat(run.getCommitsCollected()).isEqualTo(1);
        assertThat(run.getPrsCollected()).isEqualTo(1);
        assertThat(run.getIssuesCollected()).isEqualTo(1);
        assertThat(run.getPartialReason()).isEqualTo(PartialReason.CAP_EXCEEDED);

        GitCommit stored = commits.findByRepositoryIdAndSha(repositoryId, SHA_1).orElseThrow();
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getAuthorLogin()).isEqualTo("grow22");
        assertThat(stored.getAuthorName()).isEqualTo("Grow");
        assertThat(stored.getMessage()).isEqualTo("feat: 세션 인덱스 추가");
        assertThat(stored.getAuthoredAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
        assertThat(stored.getAdditions()).isEqualTo(11);
        assertThat(stored.getDeletions()).isEqualTo(22);
        assertThat(stored.getChangedFiles()).isEqualTo(33);
        assertThat(stored.getParentCount()).isEqualTo((short) 1);
        assertThat(stored.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("커밋에는 user_repository_id 가 아니라 repository_id 가 저장된다")
    void savesRepositoryIdNotUserRepositoryId() {

        service.store(request(), activityOf(commit(SHA_1)));

        Long saved = jdbc.queryForObject(
                "SELECT repository_id FROM git_commit WHERE sha = ?", Long.class, SHA_1);

        assertThat(saved).isEqualTo(repositoryId).isNotEqualTo(userRepositoryId);
    }

    @Test
    @DisplayName("이미 저장한 커밋은 다시 저장되지 않지만 개수에는 그대로 센다")
    void skipsCommitStoredBefore() {

        service.store(request(), activityOf(commit(SHA_1)));
        Long secondRunId = service.store(request(), activityOf(commit(SHA_1), commit(SHA_2)));

        assertThat(countCommits()).isEqualTo(2);
        assertThat(runs.findById(secondRunId).orElseThrow().getCommitsCollected()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 응답에 같은 커밋이 두 번 있으면 한 번만 저장된다")
    void skipsDuplicateShaInOneResponse() {

        Long runId = service.store(request(), activityOf(commit(SHA_1), commit(SHA_1)));

        assertThat(countCommits()).isEqualTo(1);
        assertThat(runs.findById(runId).orElseThrow().getCommitsCollected()).isEqualTo(2);
    }

    @Test
    @DisplayName("커밋이 하나도 없어도 수집 이력은 남는다")
    void savesRunWithoutCommits() {

        Long runId = service.store(request(), activityOf());

        assertThat(runs.findById(runId)).isPresent();
        assertThat(countCommits()).isZero();
    }

    @Test
    @DisplayName("연결 저장소가 없으면 아무것도 저장되지 않는다")
    void rejectsUnknownUserRepository() {

        IngestRequest unknown = new IngestRequest(userRepositoryId + 1000, BRANCHES, SINCE);

        assertThatThrownBy(() -> service.store(unknown, activityOf(commit(SHA_1))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runs.count()).isZero();
        assertThat(countCommits()).isZero();
    }

    private IngestRequest request() {
        return new IngestRequest(userRepositoryId, BRANCHES, SINCE);
    }

    private CollectedActivity activityOf(CollectedCommit... collected) {
        return new CollectedActivity(List.of(collected), List.of(), List.of(), null);
    }

    private CollectedCommit commit(String sha) {
        return new CollectedCommit(sha, "grow22", "Grow", "feat: 세션 인덱스 추가",
                Instant.parse("2026-09-20T09:00:00Z"), 11, 22, 33, (short) 1, null);
    }

    private int countCommits() {
        return jdbc.queryForObject("SELECT count(*) FROM git_commit", Integer.class);
    }
}
