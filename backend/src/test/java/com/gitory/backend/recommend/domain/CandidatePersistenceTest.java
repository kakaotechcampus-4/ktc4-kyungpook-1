package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CandidatePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateFixtures candidates;

    private Long repositoryId;
    private Long userRepositoryId;
    private Long commitId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        candidates = new CandidateFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        commitId = insertCommit("a".repeat(40));

    }

    @Test
    @DisplayName("후보의 칸마다 맞는 값이 저장되고 다시 읽힌다")
    void savesEveryCandidateField() {

        Candidate candidate = Candidate.grouped(userRepositoryId, CandidateType.PR, 42, "#42", List.of(42, 45),
                List.of(31, 7), "로그인 세션 처리", "리뷰 코멘트 14건이 남은 작업", new BigDecimal("0.6120"), true, 12, 14, 21,
                new BigDecimal("0.9200"));
        candidate.markUserModified();
        em.persist(candidate);
        em.flush();
        em.clear();

        Candidate found = em.find(Candidate.class, candidate.getId());

        assertThat(found.getPublicId()).isEqualTo(candidate.getPublicId());
        assertThat(found.getUserRepositoryId()).isEqualTo(userRepositoryId);
        assertThat(found.getSourceType()).isEqualTo(CandidateType.PR);
        assertThat(found.getGithubPrNumber()).isEqualTo(42);
        assertThat(found.getSourceRef()).isEqualTo("#42");
        assertThat(found.getPullRequestNumbers()).containsExactly(42, 45);
        assertThat(found.getIssueNumbers()).containsExactly(31, 7);
        assertThat(found.getTitle()).isEqualTo("로그인 세션 처리");
        assertThat(found.getReasonText()).isEqualTo("리뷰 코멘트 14건이 남은 작업");
        assertThat(found.getScore()).isEqualByComparingTo("0.612");
        assertThat(found.isLowCardWorth()).isTrue();
        assertThat(found.getFileCount()).isEqualTo(12);
        assertThat(found.getReviewCommentCount()).isEqualTo(14);
        assertThat(found.getSpanDays()).isEqualTo(21);
        assertThat(found.getCodeRatio()).isEqualByComparingTo("0.92");
        assertThat(found.getStatus()).isEqualTo(CandidateStatus.NEW);
        assertThat(found.isUserModified()).isTrue();

    }

    @Test
    @DisplayName("PR 이 없는 후보는 날짜 모양의 ref 와 코드 비율 1 이 저장되고 다시 읽힌다")
    void savesCommitClusterWithDateRefAndFullCodeRatio() {

        Candidate candidate = Candidate.grouped(userRepositoryId, CandidateType.COMMIT_CLUSTER, null, "2026-10-09",
                List.of(), List.of(), "결제 오류 수정", "하루 동안 이어진 수정", new BigDecimal("0.4000"), false, 3, 0, 1,
                BigDecimal.ONE);
        em.persist(candidate);
        em.flush();
        em.clear();

        Candidate found = em.find(Candidate.class, candidate.getId());

        assertThat(found.getSourceType()).isEqualTo(CandidateType.COMMIT_CLUSTER);
        assertThat(found.getGithubPrNumber()).isNull();
        assertThat(found.getSourceRef()).isEqualTo("2026-10-09");
        assertThat(found.getCodeRatio()).isEqualByComparingTo("1");

    }

    @Test
    @DisplayName("AI 가 후보에 넣은 커밋마다 포함된 상태로 표시 경로와 함께 저장되고 다시 읽힌다")
    void savesEveryCandidateCommitField() {

        Long candidateId = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW");
        Long secondCommitId = insertCommit("b".repeat(40));
        CandidateCommit first = CandidateCommit.grouped(candidateId, commitId, "src/payment/service.java");
        CandidateCommit second = CandidateCommit.grouped(candidateId, secondCommitId, "src/payment/refund.java");
        em.persist(first);
        em.persist(second);
        em.flush();
        em.clear();

        CandidateCommit foundFirst = em.find(CandidateCommit.class, first.getId());
        CandidateCommit foundSecond = em.find(CandidateCommit.class, second.getId());

        assertThat(foundFirst.getCandidateId()).isEqualTo(candidateId);
        assertThat(foundFirst.getCommitId()).isEqualTo(commitId);
        assertThat(foundFirst.getOrigin()).isEqualTo(CandidateCommitOrigin.AI);
        assertThat(foundFirst.isIncluded()).isTrue();
        assertThat(foundFirst.getDisplayPath()).isEqualTo("src/payment/service.java");
        assertThat(foundFirst.getUpdatedAt()).isNotNull();
        assertThat(foundSecond.getCandidateId()).isEqualTo(candidateId);
        assertThat(foundSecond.getCommitId()).isEqualTo(secondCommitId);
        assertThat(foundSecond.getDisplayPath()).isEqualTo("src/payment/refund.java");

    }

    @ParameterizedTest
    @EnumSource(CandidateStatus.class)
    @DisplayName("후보 상태 세 가지가 모두 저장되고 다시 읽힌다")
    void savesEveryStatus(CandidateStatus status) {

        Long id = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, status.name());

        assertThat(em.find(Candidate.class, id).getStatus()).isEqualTo(status);

    }

    @ParameterizedTest
    @EnumSource(CandidateType.class)
    @DisplayName("후보 종류 세 가지가 모두 저장되고 다시 읽힌다")
    void savesEveryType(CandidateType type) {

        Integer prNumber = type == CandidateType.PR ? 42 : null;
        Long id = candidates.insertCandidate(userRepositoryId, type.name(), prNumber, "NEW");

        assertThat(em.find(Candidate.class, id).getSourceType()).isEqualTo(type);

    }

    @ParameterizedTest
    @CsvSource({"COMMIT_CLUSTER, PROPOSED", "COMMIT_CLUSTER, CONFIRMED", "ISSUE, NEW"})
    @DisplayName("옛 상태 PROPOSED·CONFIRMED 와 쓰지 않는 종류 ISSUE 는 저장되지 않는다")
    void rejectsRetiredValues(String type, String status) {

        assertThatThrownBy(() -> candidates.insertCandidate(userRepositoryId, type, null, status))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("상태를 정하지 않고 넣은 후보는 NEW 다")
    void defaultsStatusToNew() {

        Long id = jdbc.queryForObject(
                "INSERT INTO candidate (user_repository_id, source_type, title, reason_text) VALUES (?, 'COMMIT_CLUSTER', '후보', '이유') RETURNING id",
                Long.class, userRepositoryId);

        assertThat(jdbc.queryForObject("SELECT status FROM candidate WHERE id = ?", String.class, id))
                .isEqualTo("NEW");

    }

    @ParameterizedTest
    @CsvSource({
            "PR,             42, NEW,      false, false",
            "PR,             42, USED,     false, true",
            "COMMIT_CLUSTER,   , EXCLUDED, false, true",
            "COMMIT_CLUSTER,   , NEW,      true,  true",
            "MANUAL,           , NEW,      false, true"})
    @DisplayName("NEW 가 아니거나 손댄 표시가 켜졌거나 직접 추가한 후보만 손댄 후보로 읽힌다")
    void readsTouchedCandidates(String type, Integer prNumber, String status, boolean userModified,
                                boolean touched) {

        Long id = candidates.insertCandidate(userRepositoryId, type, prNumber, status);
        jdbc.update("UPDATE candidate SET is_user_modified = ? WHERE id = ?", userModified, id);

        assertThat(em.find(Candidate.class, id).isTouched()).isEqualTo(touched);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "candidate.pull_request_numbers", "candidate.issue_numbers", "candidate.file_count",
            "candidate.review_comment_count", "candidate_commit.display_path"})
    @DisplayName("PR·이슈 번호 목록과 파일 수·리뷰 코멘트 수, 후보 커밋의 표시 경로는 비울 수 없다")
    void rejectsNullInNewColumns(String tableAndColumn) {

        Long candidateId = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW");
        candidates.insertCandidateCommit(candidateId, commitId);
        String[] parts = tableAndColumn.split("\\.");

        assertThatThrownBy(() -> jdbc.update("UPDATE " + parts[0] + " SET " + parts[1] + " = NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    private Long insertCommit(String sha) {

        return jdbc.queryForObject("INSERT INTO git_commit (repository_id, sha) VALUES (?, ?) RETURNING id",
                Long.class, repositoryId, sha);

    }
}
