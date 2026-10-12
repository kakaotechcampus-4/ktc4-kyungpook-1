package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gitory.backend.support.CandidateBoardFixtures;
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

import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OwnCommitFileService.class)
class OwnCommitFileServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    OwnCommitFileService service;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateBoardFixtures rows;
    private Long repositoryId;
    private Long otherRepositoryId;
    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        rows = new CandidateBoardFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        Long teammateId = fixtures.insertUser(2L, "teammate");
        otherRepositoryId = fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        fixtures.insertUserRepository(teammateId, repositoryId);
        fixtures.insertUserRepository(userId, otherRepositoryId);

    }

    @Test
    @DisplayName("작성자 로그인이 대소문자만 달라도 내 커밋으로 보고 그 커밋의 파일을 돌려준다")
    void matchesLoginIgnoringCase() {

        Long mine = rows.insertCommitBy(repositoryId, "a".repeat(40), "GROW22", null);
        rows.insertFile(mine, "src/App.java");

        List<OwnCommitFile> files = service.findFiles(userRepositoryId, "grow22");

        assertThat(files).containsExactly(new OwnCommitFile(mine, "src/App.java"));

    }

    @Test
    @DisplayName("팀원의 커밋과 작성자 계정이 없는 커밋과 BOT·MERGE_COMMIT·LOCKFILE_ONLY 로 제외된 내 커밋의 파일은 빠진다")
    void leavesOutOthersAndExcludedCommits() {

        Long mine = rows.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null);
        rows.insertFile(mine, "src/Mine.java");
        rows.insertFile(rows.insertCommitBy(repositoryId, "b".repeat(40), "teammate", null), "src/Teammate.java");
        rows.insertFile(rows.insertCommitBy(repositoryId, "c".repeat(40), null, null), "src/NoAccount.java");
        rows.insertFile(rows.insertCommitBy(repositoryId, "d".repeat(40), "grow22", "BOT"), "src/Bot.java");
        rows.insertFile(rows.insertCommitBy(repositoryId, "e".repeat(40), "grow22", "MERGE_COMMIT"), "src/Merge.java");
        rows.insertFile(rows.insertCommitBy(repositoryId, "f".repeat(40), "grow22", "LOCKFILE_ONLY"), "package-lock.json");

        List<OwnCommitFile> files = service.findFiles(userRepositoryId, "grow22");

        assertThat(files).containsExactly(new OwnCommitFile(mine, "src/Mine.java"));

    }

    @Test
    @DisplayName("바뀐 파일을 아직 채우지 않은 커밋은 파일이 없고, 같은 로그인의 다른 저장소 커밋은 섞이지 않는다")
    void ignoresUnfilledCommitsAndOtherRepositories() {

        rows.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null);
        Long inOtherRepository = rows.insertCommitBy(otherRepositoryId, "b".repeat(40), "grow22", null);
        rows.insertFile(inOtherRepository, "src/Other.java");

        assertThat(service.findFiles(userRepositoryId, "grow22")).isEmpty();

    }

    @Test
    @DisplayName("한 커밋의 여러 파일과 여러 커밋의 파일을 커밋 순서대로 모두 돌려준다")
    void returnsEveryFileOfEveryOwnCommit() {

        Long first = rows.insertCommitBy(repositoryId, "a".repeat(40), "grow22", null);
        Long second = rows.insertCommitBy(repositoryId, "b".repeat(40), "grow22", null);
        rows.insertFile(first, "A.java");
        rows.insertFile(first, "B.java");
        rows.insertFile(second, "A.java");

        List<OwnCommitFile> files = service.findFiles(userRepositoryId, "grow22");

        assertThat(files).containsExactly(new OwnCommitFile(first, "A.java"), new OwnCommitFile(first, "B.java"),
                new OwnCommitFile(second, "A.java"));

    }
}
