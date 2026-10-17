package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.ingest.infra.CommitFileRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CommitFilePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CommitFileRepository commitFiles;

    @Autowired
    JdbcTemplate jdbc;

    private Long commitId;

    @BeforeEach
    void setUp() {

        Long repositoryId = new TestFixtures(jdbc).insertRepository(1L, "grow22", "gitory");
        commitId = jdbc.queryForObject("INSERT INTO git_commit (repository_id, sha) VALUES (?, ?) RETURNING id",
                Long.class, repositoryId, "a".repeat(40));

    }

    @Test
    @DisplayName("바뀐 파일의 칸마다 맞는 값이 저장된다")
    void savesEveryField() {

        commitFiles.insertIfAbsent(CommitFile.collected(commitId, "backend/session.sql", FileChangeStatus.RENAMED,
                10, 3));

        CommitFile found = commitFiles.findAll().getFirst();
        assertThat(found.getCommitId()).isEqualTo(commitId);
        assertThat(found.getPath()).isEqualTo("backend/session.sql");
        assertThat(found.getStatus()).isEqualTo(FileChangeStatus.RENAMED);
        assertThat(found.getAdditions()).isEqualTo(10);
        assertThat(found.getDeletions()).isEqualTo(3);

    }

    @ParameterizedTest
    @EnumSource(FileChangeStatus.class)
    @DisplayName("파일 상태 여덟 가지가 모두 저장된다")
    void savesEveryStatus(FileChangeStatus status) {

        commitFiles.insertIfAbsent(CommitFile.collected(commitId, "backend/session.sql", status, 0, 0));

        assertThat(commitFiles.findAll()).extracting(CommitFile::getStatus).containsExactly(status);

    }

    @Test
    @DisplayName("여덟 가지가 아닌 파일 상태는 저장되지 않는다")
    void rejectsUnknownStatus() {

        assertThatThrownBy(() -> insert("backend/session.sql", "RESOLVED"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("경로가 없는 파일은 저장되지 않는다")
    void rejectsFileWithoutPath() {

        assertThatThrownBy(() -> insert(null, "ADDED"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("같은 커밋에 같은 경로는 두 번 저장할 수 없다")
    void rejectsDuplicatePathInSameCommit() {

        insert("backend/session.sql", "ADDED");

        assertThatThrownBy(() -> insert("backend/session.sql", "MODIFIED"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("커밋이 지워지면 그 커밋의 바뀐 파일도 같이 지워진다")
    void deletesFilesWithCommit() {

        insert("backend/session.sql", "ADDED");

        jdbc.update("DELETE FROM git_commit WHERE id = ?", commitId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM commit_file", Integer.class)).isZero();

    }

    private void insert(String path, String status) {

        jdbc.update("INSERT INTO commit_file (commit_id, path, status, additions, deletions) VALUES (?, ?, ?, 0, 0)",
                commitId, path, status);

    }
}
