package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.gitory.backend.support.StoredActivityFixtures;
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

import java.time.Instant;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(StoredActivityService.class)
class StoredActivityServiceTest {

    private static final String SHA_1 = "1".repeat(40);
    private static final String SHA_2 = "2".repeat(40);
    private static final String SHA_3 = "3".repeat(40);
    private static final String UNSTORED_SHA = "4".repeat(40);
    // 공유 계약 예시처럼 40자보다 짧은 sha 를 쓴다
    private static final String SHORT_SHA = "abc1234567890";
    private static final Instant AUTHORED_AT = Instant.parse("2026-09-23T08:15:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    StoredActivityService service;

    @Autowired
    JdbcTemplate jdbc;

    private StoredActivityFixtures activity;
    private Long repositoryId;
    private Long otherRepositoryId;
    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        // 같은 sha 를 가진 다른 저장소를 먼저 만들어, 연결이 아니라 저장소 id 로 잘못 찾으면 그 커밋이 섞이게 한다
        otherRepositoryId = fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);

    }

    @Test
    @DisplayName("저장소 연결이 가리키는 저장소의 커밋 중 요청한 sha 만 작성 시각·바뀐 파일 수·바뀐 파일과 함께 돌려준다")
    void findsRequestedCommitsOfLinkedRepository() {

        Long first = activity.insertCommit(repositoryId, SHA_1, AUTHORED_AT, 3);
        activity.insertFile(first, "backend/Session.java", 10, 2);
        activity.insertFile(first, "docs/README.md", 1, 0);
        Long second = activity.insertCommit(repositoryId, SHA_2, null, null);
        activity.insertCommit(repositoryId, SHA_3, AUTHORED_AT, 1);
        activity.insertCommit(otherRepositoryId, SHA_1, AUTHORED_AT, 9);

        List<StoredCommit> found = service.findCommits(userRepositoryId, List.of(SHA_1, SHA_2, UNSTORED_SHA));

        assertThat(found).extracting(StoredCommit::id).containsExactlyInAnyOrder(first, second);
        assertThat(found).filteredOn(commit -> commit.id().equals(first)).singleElement().satisfies(commit -> {
            assertThat(commit.sha()).isEqualTo(SHA_1);
            assertThat(commit.authoredAt()).isEqualTo(AUTHORED_AT);
            assertThat(commit.changedFiles()).isEqualTo(3);
            assertThat(commit.files()).containsExactly(new ChangedFile("backend/Session.java", 10, 2),
                    new ChangedFile("docs/README.md", 1, 0));
        });
        assertThat(found).filteredOn(commit -> commit.id().equals(second)).singleElement().satisfies(commit -> {
            assertThat(commit.sha()).isEqualTo(SHA_2);
            assertThat(commit.authoredAt()).isNull();
            assertThat(commit.changedFiles()).isNull();
            assertThat(commit.files()).isEmpty();
        });

    }

    @Test
    @DisplayName("40자보다 짧은 sha 도 뒤에 공백 없이 저장할 때의 글자 그대로 돌려준다")
    void returnsShortShaWithoutPadding() {

        Long id = activity.insertCommit(repositoryId, SHORT_SHA, AUTHORED_AT, 1);

        assertThat(service.findCommits(userRepositoryId, List.of(SHORT_SHA)))
                .extracting(StoredCommit::sha)
                .containsExactly(SHORT_SHA);
        assertThat(service.findShas(List.of(id))).containsExactly(SHORT_SHA);

    }

    @Test
    @DisplayName("커밋 id 로 그 커밋들의 sha 만 돌려준다")
    void findsShasByCommitId() {

        Long first = activity.insertCommit(repositoryId, SHA_1, AUTHORED_AT, 1);
        Long second = activity.insertCommit(otherRepositoryId, SHA_2, AUTHORED_AT, 1);
        activity.insertCommit(repositoryId, SHA_3, AUTHORED_AT, 1);

        assertThat(service.findShas(List.of(first, second))).containsExactlyInAnyOrder(SHA_1, SHA_2);

    }

    @Test
    @DisplayName("PR 번호마다 그 저장소의 줄 댓글 수를 세고, 다른 저장소의 댓글과 묻지 않은 PR 의 댓글은 세지 않는다")
    void countsReviewCommentsPerPullRequest() {

        activity.insertPullRequest(repositoryId, 42);
        activity.insertPullRequest(repositoryId, 45);
        activity.insertPullRequest(repositoryId, 50);
        activity.insertPullRequest(repositoryId, 99);
        activity.insertPullRequest(otherRepositoryId, 42);
        activity.insertReviewComment(repositoryId, 42, 1L);
        activity.insertReviewComment(repositoryId, 42, 2L);
        activity.insertReviewComment(repositoryId, 45, 3L);
        activity.insertReviewComment(repositoryId, 99, 4L);
        activity.insertReviewComment(otherRepositoryId, 42, 5L);
        activity.insertReviewComment(otherRepositoryId, 42, 6L);

        assertThat(service.countReviewComments(userRepositoryId, List.of(42, 45, 50)))
                .containsOnly(entry(42, 2L), entry(45, 1L));

    }

    @Test
    @DisplayName("빈 목록을 주면 쿼리가 실패하지 않고 빈 결과다")
    void emptyRequestsReturnNothing() {

        activity.insertCommit(repositoryId, SHA_1, AUTHORED_AT, 1);

        assertThat(service.findCommits(userRepositoryId, List.of())).isEmpty();
        assertThat(service.findShas(List.of())).isEmpty();
        assertThat(service.countReviewComments(userRepositoryId, List.of())).isEmpty();

    }
}
