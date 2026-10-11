package com.gitory.backend.card.domain;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(CardVersionWriter.class)
class CardVersionWriterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardVersionWriter writer;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TestFixtures fixtures;

    private Long userId;
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);

        userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("막 저장한 카드의 1번 버전을 받은 출처로, 확정본이 아니고 문장 없는 버전으로 만든다")
    void startFirstCreatesEmptyFirstVersion() {

        Card card = Card.startManual(userId, null, "카드", null, null);
        em.persist(card);

        CardVersion first = writer.startFirst(card, VersionSource.AI_DRAFT);
        em.flush();

        assertThat(first.getVersionNo()).isEqualTo((short) 1);
        assertThat(versionsOf(card.getId())).containsExactly(new VersionRow(1, "AI_DRAFT", false));
        assertThat(slotsOf(card.getId(), 1)).isEmpty();

    }

    @Test
    @DisplayName("다음 번호의 빈 버전을 받은 출처로 만들어 카드의 현재 버전으로 삼고 앞 버전 문장은 그대로 둔다")
    void startNextCreatesEmptyNextVersion() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(cardId, 1, "S", "1번 버전 글", "USER_STATED", null);
        Card card = em.find(Card.class, cardId);

        CardVersion next = writer.startNext(card, VersionSource.MASK);
        em.flush();

        assertThat(next.getVersionNo()).isEqualTo((short) 2);
        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "MASK", false));
        assertThat(currentVersionOf(cardId)).isEqualTo(2);
        assertThat(slotsOf(cardId, 2)).isEmpty();
        assertThat(slotsOf(cardId, 1)).containsExactly(
                new SlotRow("S", 1, "1번 버전 글", "USER_STATED", null, null, List.of()));

    }

    @Test
    @DisplayName("확정된 카드에 새 버전을 만들면 CardConfirmedException 이고 버전 행도 현재 번호도 그대로다")
    void startNextOnConfirmedCardLeavesNothing() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.confirmCard(cardId);
        Card card = em.find(Card.class, cardId);

        assertThatThrownBy(() -> writer.startNext(card, VersionSource.USER_EDIT))
                .isInstanceOf(CardConfirmedException.class);
        em.flush();

        assertThat(versionsOf(cardId)).containsExactly(new VersionRow(1, "USER_EDIT", false));
        assertThat(currentVersionOf(cardId)).isEqualTo(1);

    }

    // 실패한 트랜잭션이 끝난 뒤의 DB 를 보려고 테스트 트랜잭션 없이 돌리므로, 커밋된 행은 끝에 직접 지운다
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("현재 버전이 32767 인 카드에 새 버전을 만들면 실패하고, 버전 행과 현재 번호가 그대로다")
    void startNextBeyondLastNumberSavesNothing() {

        try {
            Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
            fixtures.insertCardVersion(cardId, 32767, "USER_EDIT", false);

            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> writer.startNext(em.find(Card.class, cardId), VersionSource.USER_EDIT)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("card_version_version_no_check");

            assertThat(versionsOf(cardId)).containsExactly(
                    new VersionRow(1, "USER_EDIT", false),
                    new VersionRow(32767, "USER_EDIT", false));
            assertThat(currentVersionOf(cardId)).isEqualTo(32767);
        } finally {
            fixtures.clear();
        }

    }

    @Test
    @DisplayName("고른 버전의 네 칸 문장을 칸·글·출처·확신도·턴과 커밋 연결까지 다음 번호로 복사해 현재 버전으로 삼고, 고른 버전은 그대로 둔다")
    void copyToNextCopiesEverySlot() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "T");
        Long situation = fixtures.insertStatement(cardId, 1, "S", "  상황 글 ", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, secondCommit);
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long task = fixtures.insertStatement(cardId, 1, "T", "고른 과제", "USER_SELECTED", "MEDIUM", turnId);
        Long action = fixtures.insertStatement(cardId, 1, "A", "직접 쓴 행동", "USER_STATED", null);
        Long result = fixtures.insertStatement(cardId, 1, "R", "결과 글", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, firstCommit);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "2번 버전에서 고친 글", "USER_STATED", null);
        Card card = em.find(Card.class, cardId);

        CardVersion copied = writer.copyToNext(card, (short) 1, VersionSource.RESTORE);
        em.flush();

        List<SlotRow> firstVersion = List.of(
                new SlotRow("A", 1, "직접 쓴 행동", "USER_STATED", null, null, List.of()),
                new SlotRow("R", 1, "결과 글", "COMMIT", "LOW", null, List.of(firstCommit)),
                new SlotRow("S", 1, "  상황 글 ", "COMMIT", "HIGH", null, List.of(secondCommit, firstCommit)),
                new SlotRow("T", 1, "고른 과제", "USER_SELECTED", "MEDIUM", turnId, List.of()));
        assertThat(copied.getVersionNo()).isEqualTo((short) 3);
        assertThat(versionsOf(cardId)).last().isEqualTo(new VersionRow(3, "RESTORE", false));
        assertThat(currentVersionOf(cardId)).isEqualTo(3);
        assertThat(slotsOf(cardId, 3)).isEqualTo(firstVersion);
        assertThat(slotsOf(cardId, 1)).isEqualTo(firstVersion);
        assertThat(statementIdsOf(cardId, 1)).containsExactly(situation, task, action, result);
        assertThat(slotsOf(cardId, 2)).containsExactly(
                new SlotRow("S", 1, "2번 버전에서 고친 글", "USER_STATED", null, null, List.of()));

    }

    @Test
    @DisplayName("없는 버전에서 복사하면 CardVersionNotFoundException 이고 새 버전을 만들지 않는다")
    void copyFromMissingVersionCreatesNothing() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        Card card = em.find(Card.class, cardId);

        assertThatThrownBy(() -> writer.copyToNext(card, (short) 5, VersionSource.RESTORE))
                .isInstanceOf(CardVersionNotFoundException.class);
        em.flush();

        assertThat(versionsOf(cardId)).containsExactly(new VersionRow(1, "USER_EDIT", false));
        assertThat(currentVersionOf(cardId)).isEqualTo(1);

    }

    @Test
    @DisplayName("한 칸의 문장과 커밋 연결만 다른 버전으로 복사하고, 그 칸에 문장이 없으면 아무것도 복사하지 않는다")
    void copySlotCopiesOnlyThatSlot() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "상황 글", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertStatement(cardId, 1, "A", "행동 글", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);

        writer.copySlot(cardId, (short) 1, StarField.S, (short) 2);
        writer.copySlot(cardId, (short) 1, StarField.R, (short) 2);
        em.flush();

        assertThat(slotsOf(cardId, 2)).containsExactly(
                new SlotRow("S", 1, "상황 글", "COMMIT", "LOW", null, List.of(firstCommit)));

    }

    private List<VersionRow> versionsOf(Long cardId) {

        return jdbc.query("SELECT version_no, source, is_confirmed FROM card_version WHERE card_id = ? ORDER BY version_no",
                (row, rowNum) -> new VersionRow(row.getInt("version_no"), row.getString("source"),
                        row.getBoolean("is_confirmed")), cardId);

    }

    private int currentVersionOf(Long cardId) {

        return jdbc.queryForObject("SELECT current_version FROM card WHERE id = ?", Integer.class, cardId);

    }

    private List<Long> statementIdsOf(Long cardId, int versionNo) {

        return jdbc.queryForList("SELECT id FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY id",
                Long.class, cardId, versionNo);

    }

    private List<SlotRow> slotsOf(Long cardId, int versionNo) {

        return jdbc.query("""
                        SELECT id, star_slot, seq, body, evidence_type, confidence, source_turn_id
                        FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY star_slot
                        """,
                (row, rowNum) -> new SlotRow(row.getString("star_slot"), row.getInt("seq"), row.getString("body"),
                        row.getString("evidence_type"), row.getString("confidence"),
                        row.getObject("source_turn_id", Long.class), commitIdsOf(row.getLong("id"))),
                cardId, versionNo);

    }

    private List<Long> commitIdsOf(Long statementId) {

        return jdbc.queryForList("SELECT commit_id FROM statement_evidence WHERE statement_id = ? ORDER BY id",
                Long.class, statementId);

    }

    private record VersionRow(int versionNo, String source, boolean confirmed) {
    }

    private record SlotRow(String slot, int seq, String body, String evidenceType, String confidence, Long turnId,
                           List<Long> commitIds) {
    }
}
