package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CardPersistenceTest {

    private static final String SHA = "abc1234567890abc1234567890abc1234567890a";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CardRepository cards;

    @Autowired
    CardStatementRepository statements;

    private TestFixtures fixtures;

    private Long userId;
    private Long otherUserId;
    private Long repoLinkId;
    private Long commitId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);

        userId = fixtures.insertUser(1L, "grow22");
        otherUserId = fixtures.insertUser(2L, "taehun0208");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        repoLinkId = fixtures.insertUserRepository(userId, repositoryId);
        commitId = fixtures.insertCommit(repositoryId, SHA);

    }

    @Test
    @DisplayName("카드의 칸마다 값이 시각까지 그대로 저장되고 다시 읽히며, 가리기 규칙은 from·to 이름의 JSON 배열로 저장된다")
    void savesCardFields() {

        Card card = Card.startManual(userId, repoLinkId, " 카드 제목 ", "2024.04", "key-1");
        card.replaceMaskRules(List.of(new MaskRule("홍길동", "팀원 A"), new MaskRule(" 카카오 ", "")));
        card.confirm();
        em.persist(card);
        em.flush();
        em.clear();

        Card found = em.find(Card.class, card.getId());

        assertThat(found.getPublicId()).isEqualTo(card.getPublicId());
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getUserRepositoryId()).isEqualTo(repoLinkId);
        assertThat(found.getCardType()).isEqualTo(CardKind.QUALITATIVE);
        assertThat(found.getOrigin()).isEqualTo(CardOrigin.MANUAL);
        assertThat(found.getTitle()).isEqualTo(" 카드 제목 ");
        assertThat(found.getPeriod()).isEqualTo("2024.04");
        assertThat(found.getStatus()).isEqualTo(CardStatus.CONFIRMED);
        assertThat(found.getCurrentVersion()).isEqualTo((short) 1);
        assertThat(found.getMaskRules()).containsExactly(new MaskRule("홍길동", "팀원 A"), new MaskRule(" 카카오 ", ""));
        assertThat(found.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(found.getCreatedAt()).isEqualTo(card.getCreatedAt());
        assertThat(found.getUpdatedAt()).isEqualTo(card.getUpdatedAt());
        assertThat(found.getConfirmedAt()).isEqualTo(card.getConfirmedAt());
        assertThat(jdbc.queryForObject("SELECT mask_rules -> 1 ->> 'from' FROM card WHERE id = ?", String.class,
                card.getId())).isEqualTo(" 카카오 ");

    }

    @Test
    @DisplayName("카드 버전의 칸마다 값이 시각까지 그대로 저장되고 다시 읽힌다")
    void savesCardVersionFields() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        CardVersion version = CardVersion.of(cardId, (short) 2, VersionSource.RESTORE);
        version.markConfirmed();
        em.persist(version);
        em.flush();
        em.clear();

        CardVersion found = em.find(CardVersion.class, version.getId());

        assertThat(found.getCardId()).isEqualTo(cardId);
        assertThat(found.getVersionNo()).isEqualTo((short) 2);
        assertThat(found.getSource()).isEqualTo(VersionSource.RESTORE);
        assertThat(found.isConfirmed()).isTrue();
        assertThat(found.getUpdatedAt()).isEqualTo(version.getUpdatedAt());

    }

    @Test
    @DisplayName("카드 문장의 칸마다 값이 저장되고 다시 읽힌다")
    void savesStatementFields() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "T");
        Long selectedId = fixtures.insertStatement(cardId, 1, "T", "  고른 답  ", "USER_SELECTED", "MEDIUM", turnId);

        CardStatement written = statements.save(CardStatement.userStated(cardId, (short) 1, StarField.S, " 직접 쓴 글 "));
        em.flush();
        em.clear();

        CardStatement selected = em.find(CardStatement.class, selectedId);
        assertThat(selected.getCardId()).isEqualTo(cardId);
        assertThat(selected.getVersionNo()).isEqualTo((short) 1);
        assertThat(selected.getStarSlot()).isEqualTo(StarField.T);
        assertThat(selected.getSeq()).isEqualTo((short) 1);
        assertThat(selected.getBody()).isEqualTo("  고른 답  ");
        assertThat(selected.getEvidenceType()).isEqualTo(EvidenceType.USER_SELECTED);
        assertThat(selected.getConfidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(selected.getSourceTurnId()).isEqualTo(turnId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT star_slot, seq, body, evidence_type, confidence, source_turn_id FROM card_statement WHERE id = ?",
                written.getId());
        assertThat(row.get("star_slot")).isEqualTo("S");
        assertThat(row.get("seq")).isEqualTo(1);
        assertThat(row.get("body")).isEqualTo(" 직접 쓴 글 ");
        assertThat(row.get("evidence_type")).isEqualTo("USER_STATED");
        assertThat(row.get("confidence")).isNull();
        assertThat(row.get("source_turn_id")).isNull();

    }

    @Test
    @DisplayName("문장과 근거 커밋의 연결이 저장되고 다시 읽힌다")
    void savesStatementEvidence() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        Long statementId = fixtures.insertStatement(cardId, 1, "S", "근거 있는 글", "COMMIT", "HIGH");
        StatementEvidence link = StatementEvidence.link(statementId, commitId);
        em.persist(link);
        em.flush();
        em.clear();

        StatementEvidence found = em.find(StatementEvidence.class, link.getId());

        assertThat(found.getStatementId()).isEqualTo(statementId);
        assertThat(found.getCommitId()).isEqualTo(commitId);

    }

    @Test
    @DisplayName("버전 번호는 카드마다 따로 매기지만 한 카드에 같은 번호의 버전은 둘일 수 없다")
    void rejectsDuplicateVersionNo() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertCard(UUID.randomUUID(), userId);

        assertThatThrownBy(() -> fixtures.insertCardVersion(cardId, 1, "MASK", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("card_version_card_id_version_no_key");

    }

    @Test
    @DisplayName("버전 출처는 다섯 값만 받고, 확정본 표시를 적지 않으면 FALSE 로 저장된다")
    void checksVersionSourceAndDefault() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        List<String> sources = List.of("AI_DRAFT", "USER_EDIT", "INTERVIEW", "MASK", "RESTORE");
        for (int i = 0; i < sources.size(); i++) {
            jdbc.update("INSERT INTO card_version (card_id, version_no, source) VALUES (?, ?, ?)",
                    cardId, i + 2, sources.get(i));
        }

        assertThat(jdbc.queryForList("SELECT is_confirmed FROM card_version WHERE card_id = ? AND version_no > 1",
                Boolean.class, cardId)).hasSize(5).containsOnly(false);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO card_version (card_id, version_no, source) VALUES (?, 7, 'COPY')", cardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("card_version_source_check");

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("버전 번호는 1 이상만 저장된다")
    void versionNoMustBePositive(int versionNo) {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);

        assertThatThrownBy(() -> fixtures.insertCardVersion(cardId, versionNo, "USER_EDIT", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("card_version_version_no_check");

    }

    @Test
    @DisplayName("문장은 그 카드에 있는 버전 번호에만 붙일 수 있다")
    void statementNeedsExistingVersion() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);

        assertThatThrownBy(() -> fixtures.insertStatement(cardId, 2, "S", "없는 버전의 글", "USER_STATED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("card_statement_version_fk");

    }

    @Test
    @DisplayName("버전 행을 지우면 그 버전의 문장과 근거 연결이 함께 지워지고 다른 버전은 남는다")
    void deletingVersionDeletesItsStatements() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        Long kept = fixtures.insertStatement(cardId, 1, "S", "1번 버전 글", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(kept, commitId);
        Long removed = fixtures.insertStatement(cardId, 2, "S", "2번 버전 글", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(removed, commitId);

        jdbc.update("DELETE FROM card_version WHERE card_id = ? AND version_no = 2", cardId);

        assertThat(jdbc.queryForList("SELECT id FROM card_statement WHERE card_id = ?", Long.class, cardId))
                .containsExactly(kept);
        assertThat(jdbc.queryForList("SELECT statement_id FROM statement_evidence", Long.class)).containsExactly(kept);

    }

    @Test
    @DisplayName("카드 제목은 256자까지 저장되고 257자는 저장되지 않는다")
    void titleFitsUpTo256() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);

        jdbc.update("UPDATE card SET title = ? WHERE id = ?", "가".repeat(256), cardId);
        assertThat(jdbc.queryForObject("SELECT char_length(title) FROM card WHERE id = ?", Integer.class, cardId))
                .isEqualTo(256);

        assertThatThrownBy(() -> jdbc.update("UPDATE card SET title = ? WHERE id = ?", "가".repeat(257), cardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("character varying(256)");

    }

    @Test
    @DisplayName("카드 기간은 비워 둘 수 있고 50자까지 저장되며 51자는 저장되지 않는다")
    void periodIsNullOrUpTo50() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        assertThat(jdbc.queryForObject("SELECT period FROM card WHERE id = ?", String.class, cardId)).isNull();

        jdbc.update("UPDATE card SET period = ? WHERE id = ?", "가".repeat(50), cardId);
        assertThat(jdbc.queryForObject("SELECT char_length(period) FROM card WHERE id = ?", Integer.class, cardId))
                .isEqualTo(50);

        assertThatThrownBy(() -> jdbc.update("UPDATE card SET period = ? WHERE id = ?", "가".repeat(51), cardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("character varying(50)");

    }

    @Test
    @DisplayName("가리기 규칙 칸은 적지 않으면 빈 배열이고 배열이 아닌 JSON 은 저장되지 않는다")
    void maskRulesDefaultToEmptyArray() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        assertThat(jdbc.queryForObject("SELECT mask_rules::text FROM card WHERE id = ?", String.class, cardId))
                .isEqualTo("[]");

        assertThatThrownBy(() -> jdbc.update("UPDATE card SET mask_rules = '{}'::jsonb WHERE id = ?", cardId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("card_mask_rules_is_array");

    }

    @Test
    @DisplayName("중복 방지 키가 없는 카드는 한 사용자에게 여러 장일 수 있고 다른 사용자는 같은 키를 쓸 수 있다")
    void allowsNullKeysAndOtherUsersKey() {

        insertCardWithKey(userId, null);
        insertCardWithKey(userId, null);
        insertCardWithKey(userId, "key-1");
        insertCardWithKey(otherUserId, "key-1");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(4);

    }

    @Test
    @DisplayName("같은 사용자가 같은 중복 방지 키로 카드를 두 장 만들 수 없다")
    void rejectsSameKeyForSameUser() {

        insertCardWithKey(userId, "key-1");

        assertThatThrownBy(() -> insertCardWithKey(userId, "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_card_idempotency");

    }

    @Test
    @DisplayName("카드 목록은 그 사용자 카드만 최근에 고친 순으로, 고친 시각이 같으면 나중에 만든 카드부터 돌려준다")
    void listsMyCardsByUpdatedAt() {

        Long latest = fixtures.insertCard(UUID.randomUUID(), userId);
        Long oldest = fixtures.insertCard(UUID.randomUUID(), userId);
        Long tiedEarlier = fixtures.insertCard(UUID.randomUUID(), userId);
        Long tiedLater = fixtures.insertCard(UUID.randomUUID(), userId);
        Long others = fixtures.insertCard(UUID.randomUUID(), otherUserId);
        touch(latest, "2026-10-03T00:00:00Z");
        touch(oldest, "2026-10-01T00:00:00Z");
        touch(tiedEarlier, "2026-10-02T00:00:00Z");
        touch(tiedLater, "2026-10-02T00:00:00Z");
        touch(others, "2026-10-04T00:00:00Z");

        List<Long> listed = cards.findByUserIdOrderByUpdatedAtDescIdDesc(userId).stream().map(Card::getId).toList();

        assertThat(listed).containsExactly(latest, tiedLater, tiedEarlier, oldest);

    }

    @Test
    @DisplayName("한 버전의 문장을 지우면 그 카드 그 버전의 문장과 근거 연결만 지워지고, 같은 트랜잭션에서 같은 칸을 바로 다시 넣을 수 있다")
    void deletesOneVersionAndReinsertsSameSlot() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        Long otherCardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertCardVersion(otherCardId, 2, "USER_EDIT", false);
        Long firstVersion = fixtures.insertStatement(cardId, 1, "S", "1번 버전 글", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(firstVersion, commitId);
        Long replaced = fixtures.insertStatement(cardId, 2, "S", "2번 버전 글", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(replaced, commitId);
        fixtures.insertStatement(cardId, 2, "A", "2번 버전 행동", "USER_STATED", null);
        Long otherCard = fixtures.insertStatement(otherCardId, 2, "S", "다른 카드 글", "USER_STATED", null);

        statements.deleteAllOfVersion(cardId, (short) 2);
        CardStatement rewritten = statements.save(CardStatement.userStated(cardId, (short) 2, StarField.S, "새 글"));
        em.flush();

        assertThat(jdbc.queryForList("SELECT id FROM card_statement ORDER BY id", Long.class))
                .containsExactly(firstVersion, otherCard, rewritten.getId());
        assertThat(jdbc.queryForList("SELECT statement_id FROM statement_evidence", Long.class))
                .containsExactly(firstVersion);

    }

    private void insertCardWithKey(Long ownerId, String key) {

        jdbc.update("INSERT INTO card (user_id, card_type, origin, title, idempotency_key) VALUES (?, 'QUALITATIVE', 'MANUAL', '카드', ?)",
                ownerId, key);

    }

    private void touch(Long cardId, String updatedAt) {

        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.parse(updatedAt)), cardId);

    }
}
