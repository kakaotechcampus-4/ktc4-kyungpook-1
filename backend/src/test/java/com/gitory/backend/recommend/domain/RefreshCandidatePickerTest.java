package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.support.CandidateCardFixtures;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({RefreshCandidatePicker.class, CommitSummaryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshCandidatePickerTest {

    private static final Instant PREVIOUS = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER_PREVIOUS = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant BEFORE_PREVIOUS = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RefreshCandidatePicker picker;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateCardFixtures candidates;
    private StoredActivityFixtures activity;
    private Long repositoryId;
    private Long userRepositoryId;
    private Long thisRun;
    private Long earlierRun;
    private int shaSeq;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidates = new CandidateCardFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        thisRun = insertRun();
        earlierRun = insertRun();
        shaSeq = 0;

    }

    @Test
    @DisplayName("이전 분석 뒤에 쓴 커밋이나 이번 수집에서 처음 저장된 커밋이 든 새 후보만 고르고, 옛 커밋만 든 후보·뺀 커밋·쓴 후보·제외한 후보는 고르지 않는다")
    void picksOnlyNewCandidatesWithNewWork() {

        Long byTime = candidateWith("시간이 새 커밋", "NEW", true, commit(AFTER_PREVIOUS, earlierRun), 5, false);
        Long byRun = candidateWith("이번 수집에서 처음 저장", "NEW", true, commit(BEFORE_PREVIOUS, thisRun), 4, false);
        candidateWith("옛 커밋만", "NEW", true, commit(BEFORE_PREVIOUS, earlierRun), 3, false);
        candidateWith("새 커밋을 후보에서 뺌", "NEW", false, commit(AFTER_PREVIOUS, thisRun), 3, false);
        candidateWith("이미 쓴 후보", "USED", true, commit(AFTER_PREVIOUS, thisRun), 3, false);
        candidateWith("제외한 후보", "EXCLUDED", true, commit(AFTER_PREVIOUS, thisRun), 3, false);

        assertThat(picker.pick(userRepositoryId, PREVIOUS, thisRun, 8))
                .containsExactly(publicIdOf(byTime), publicIdOf(byRun));

    }

    @Test
    @DisplayName("카드감이 낮지 않은 새 후보가 있으면 점수가 높은 순으로 상한까지만 고르고 카드감이 낮은 후보는 고르지 않는다")
    void prefersWorthyCandidatesByScoreUpToTheLimit() {

        candidateWith("낮지만 점수 높음", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 9, true);
        candidateWith("셋째", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 3, false);
        Long first = candidateWith("첫째", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 5, false);
        Long second = candidateWith("둘째", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 4, false);

        assertThat(picker.pick(userRepositoryId, PREVIOUS, thisRun, 2))
                .containsExactly(publicIdOf(first), publicIdOf(second));

    }

    @Test
    @DisplayName("카드감이 낮지 않은 새 후보가 하나도 없을 때만 카드감이 낮은 후보를 같은 방식으로 고른다")
    void fallsBackToLowCardWorthWhenNothingElse() {

        candidateWith("낮음 점수 2", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 2, true);
        Long best = candidateWith("낮음 점수 7", "NEW", true, commit(AFTER_PREVIOUS, thisRun), 7, true);
        candidateWith("카드감 있지만 옛 커밋뿐", "NEW", true, commit(BEFORE_PREVIOUS, earlierRun), 9, false);

        assertThat(picker.pick(userRepositoryId, PREVIOUS, thisRun, 1)).containsExactly(publicIdOf(best));

    }

    private Long candidateWith(String title, String status, boolean included, Long commitId, int score,
                               boolean lowCardWorth) {

        Long candidateId = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "5." + title, title,
                status);
        candidates.insertCandidateCommit(candidateId, commitId, included);
        jdbc.update("UPDATE candidate SET score = ?, low_card_worth = ? WHERE id = ?", score / 10.0, lowCardWorth,
                candidateId);

        return candidateId;

    }

    private Long commit(Instant authoredAt, Long collectionRunId) {

        Long commitId = activity.insertCommit(repositoryId, String.format("%040x", ++shaSeq), authoredAt, 1);
        jdbc.update("UPDATE git_commit SET collection_run_id = ? WHERE id = ?", collectionRunId, commitId);

        return commitId;

    }

    private Long insertRun() {

        return jdbc.queryForObject("INSERT INTO collection_run (user_repository_id) VALUES (?) RETURNING id",
                Long.class, userRepositoryId);

    }

    private UUID publicIdOf(Long candidateId) {

        return candidates.publicIdOf(candidateId);

    }
}
