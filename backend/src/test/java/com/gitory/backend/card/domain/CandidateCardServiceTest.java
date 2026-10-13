package com.gitory.backend.card.domain;

import com.gitory.backend.support.CandidateCardFixtures;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CandidateCardService.class, CardVersionWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CandidateCardServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CandidateCardService service;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateCardFixtures candidateFixtures;
    private Long userId;
    private Long userRepositoryId;
    private Long candidateId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateFixtures = new CandidateCardFixtures(jdbc);
        userId = fixtures.insertUser(1L, "grow22");
        userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        candidateId = candidateFixtures.insertNewCandidate(userRepositoryId, "후보");

    }

    @Test
    @DisplayName("이미 살아 있는 카드가 있는 후보로 카드를 또 만들면 DB 유니크에 막혀 이미 쓴 후보 예외를 던진다")
    void secondLiveCardForSameCandidateIsRejected() {

        candidateFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, UUID.randomUUID(), false);

        assertThatThrownBy(() -> create(candidateId)).isInstanceOf(CandidateAlreadyUsedException.class);

        assertThat(count("card")).isEqualTo(1);

    }

    @Test
    @DisplayName("지운 카드(deleted_at)는 그 후보로 새 카드를 만드는 것을 막지 않는다")
    void deletedCardDoesNotBlockCandidate() {

        Long deleted = candidateFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, UUID.randomUUID(), false);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);

        List<UUID> created = create(candidateId);

        assertThat(created).hasSize(1);
        assertThat(count("card")).isEqualTo(2);

    }

    @Test
    @DisplayName("후보가 서로 다르면 같은 저장소에서 카드를 여러 장 만들 수 있다")
    void differentCandidatesMakeDifferentCards() {

        Long other = candidateFixtures.insertNewCandidate(userRepositoryId, "다른 후보");

        List<UUID> created = create(candidateId, other);

        assertThat(created).hasSize(2).doesNotHaveDuplicates();

    }

    @Test
    @DisplayName("후보 유니크가 아닌 다른 무결성 위반(없는 후보)은 이미 쓴 후보로 바꾸지 않고 그대로 던진다")
    void otherIntegrityViolationsPassThrough() {

        assertThatThrownBy(() -> create(candidateId + 1000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(CandidateAlreadyUsedException.class);

    }

    @Test
    @DisplayName("과제 글이 있으면 1번 버전의 T 칸에 근거 커밋과 확신도 없는 사용자 진술 문장 하나만 넣고 다른 칸은 비워 둔다")
    void taskTextFillsOnlyTaskSlotOfFirstVersion() {

        UUID cardId = createWithTask(candidateId, "코드 리뷰를 받으며 로그인 흐름을 정리하려던 중이었다").get(0);

        assertThat(statementsOf(cardId)).hasSize(1).first().satisfies(row -> {
            assertThat(((Number) row.get("version_no")).intValue()).isEqualTo(1);
            assertThat(row.get("star_slot")).isEqualTo("T");
            assertThat(((Number) row.get("seq")).intValue()).isEqualTo(1);
            assertThat(row.get("body")).isEqualTo("코드 리뷰를 받으며 로그인 흐름을 정리하려던 중이었다");
            assertThat(row.get("evidence_type")).isEqualTo("USER_STATED");
            assertThat(row.get("confidence")).isNull();
            assertThat(row.get("source_turn_id")).isNull();
        });
        assertThat(count("statement_evidence")).isZero();

    }

    @Test
    @DisplayName("과제 글은 앞뒤 공백과 줄바꿈까지 받은 그대로 저장한다")
    void taskTextIsStoredAsReceived() {

        UUID cardId = createWithTask(candidateId, "  첫째 줄\n둘째 줄  ").get(0);

        assertThat(statementsOf(cardId)).extracting(row -> row.get("body")).containsExactly("  첫째 줄\n둘째 줄  ");

    }

    @Test
    @DisplayName("과제 글이 없거나 공백뿐이면 카드만 만들고 문장 행은 만들지 않는다")
    void noTaskTextMakesNoStatement() {

        Long blank = candidateFixtures.insertNewCandidate(userRepositoryId, "공백 후보");

        UUID withoutText = createWithTask(candidateId, null).get(0);
        UUID withBlankText = createWithTask(blank, " \u00A0\t").get(0);

        assertThat(statementsOf(withoutText)).isEmpty();
        assertThat(statementsOf(withBlankText)).isEmpty();
        assertThat(count("card")).isEqualTo(2);

    }

    @Test
    @DisplayName("과제 글이 있는 카드가 여러 장이어도 문장은 자기 카드에만 들어간다")
    void taskTextGoesToItsOwnCardOnly() {

        Long other = candidateFixtures.insertNewCandidate(userRepositoryId, "다른 후보");
        List<CandidateCardSeed> seeds = List.of(
                new CandidateCardSeed(candidateId, "첫째", CardKind.QUALITATIVE, "첫째 글"),
                new CandidateCardSeed(other, "둘째", CardKind.TECH, null));

        List<UUID> created = transaction.execute(status -> service.createAll(userId, userRepositoryId, UUID.randomUUID(), seeds));

        assertThat(statementsOf(created.get(0))).extracting(row -> row.get("body")).containsExactly("첫째 글");
        assertThat(statementsOf(created.get(1))).isEmpty();

    }

    @Test
    @DisplayName("요청 트랜잭션 밖에서는 카드를 만들 수 없다")
    void requiresRunningTransaction() {

        assertThatThrownBy(() -> service.createAll(userId, userRepositoryId, UUID.randomUUID(),
                List.of(new CandidateCardSeed(candidateId, "후보", CardKind.TECH, null))))
                .isInstanceOf(IllegalTransactionStateException.class);

    }

    private List<UUID> create(Long... candidateIds) {

        UUID jobId = UUID.randomUUID();
        List<CandidateCardSeed> seeds = Arrays.stream(candidateIds)
                .map(id -> new CandidateCardSeed(id, "후보", CardKind.TECH, null))
                .toList();

        return transaction.execute(status -> service.createAll(userId, userRepositoryId, jobId, seeds));

    }

    private List<UUID> createWithTask(Long candidateId, String taskText) {

        List<CandidateCardSeed> seeds = List.of(new CandidateCardSeed(candidateId, "후보", CardKind.QUALITATIVE, taskText));

        return transaction.execute(status -> service.createAll(userId, userRepositoryId, UUID.randomUUID(), seeds));

    }

    private List<Map<String, Object>> statementsOf(UUID cardPublicId) {

        return jdbc.queryForList(
                "SELECT s.version_no, s.star_slot, s.seq, s.body, s.evidence_type, s.confidence, s.source_turn_id FROM card_statement s JOIN card c ON c.id = s.card_id WHERE c.public_id = ?",
                cardPublicId);

    }

    private int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }
}
