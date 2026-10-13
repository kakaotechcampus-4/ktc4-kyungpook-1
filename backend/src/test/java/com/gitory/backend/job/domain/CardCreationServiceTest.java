package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CandidateAlreadyUsedException;
import com.gitory.backend.card.domain.CandidateCardService;
import com.gitory.backend.card.domain.CardVersionWriter;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.recommend.domain.CandidateType;
import com.gitory.backend.recommend.domain.CandidateUseService;
import com.gitory.backend.recommend.domain.GroupedCandidate;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CardCreationService.class, CandidateUseService.class, CandidateCardService.class, CardVersionWriter.class,
        CandidateStoreService.class, StoredActivityService.class, RepositoryOwnershipService.class, AuditLog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CardCreationServiceTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);
    private static final String FINGERPRINT = "f".repeat(64);
    private static final String OTHER_FINGERPRINT = "0".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardCreationService service;

    @Autowired
    CandidateStoreService store;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateCardFixtures candidateFixtures;
    private StoredActivityFixtures activity;
    private Long userId;
    private Long repositoryId;
    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateFixtures = new CandidateCardFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);

    }

    @Test
    @DisplayName("카드로 만든 뒤 저장소를 다시 분석해도 카드로 쓴 후보와 카드의 후보 연결은 그대로이고, 손대지 않은 새 후보만 새 결과로 바뀐다")
    void reanalysisKeepsUsedCandidateAndCardLink() {

        activity.insertCommit(repositoryId, SHA_A, Instant.parse("2026-09-01T01:00:00Z"), 1);
        activity.insertCommit(repositoryId, SHA_B, Instant.parse("2026-09-02T01:00:00Z"), 1);
        activity.insertCommit(repositoryId, SHA_C, Instant.parse("2026-09-03T01:00:00Z"), 1);
        store.store(userRepositoryId, FINGERPRINT, List.of(cluster("1.1", SHA_A), cluster("2.2", SHA_B)));
        Long used = idOfTitle("1.1 작업");
        Long untouched = idOfTitle("2.2 작업");

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(used)));
        store.store(userRepositoryId, OTHER_FINGERPRINT, List.of(cluster("3.3", SHA_C)));

        assertThat(jdbc.queryForObject("SELECT status FROM candidate WHERE id = ?", String.class, used))
                .isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate WHERE id = ?", Integer.class, untouched))
                .isZero();
        assertThat(jdbc.queryForList("SELECT title FROM candidate ORDER BY id", String.class))
                .containsExactly("1.1 작업", "3.3 작업");
        assertThat(jdbc.queryForObject("SELECT candidate_id FROM card WHERE public_id = ?", Long.class,
                created.cardIds().get(0))).isEqualTo(used);

    }

    @Test
    @DisplayName("카드를 만들다 후보 하나로 살아 있는 카드가 둘이 되려 하면 409 로 답할 예외를 던지고, 후보 상태와 초안 Job 까지 모두 되돌린다")
    void uniqueIndexViolationRollsBackEverything() {

        Long first = candidateFixtures.insertNewCandidate(userRepositoryId, "첫째");
        Long carded = candidateFixtures.insertNewCandidate(userRepositoryId, "이미 카드가 있는 후보");
        candidateFixtures.insertCandidateCard(userId, userRepositoryId, carded, UUID.randomUUID(), false);

        assertThatThrownBy(() -> service.create(userId, userRepositoryId,
                List.of(publicIdOf(first), publicIdOf(carded))))
                .isInstanceOf(CandidateAlreadyUsedException.class);

        assertThat(candidateFixtures.statusOf(first)).isEqualTo("NEW");
        assertThat(candidateFixtures.statusOf(carded)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isZero();

    }

    @Test
    @DisplayName("커밋이 없는 직접 추가·회상 후보로 카드를 만들면 정성 카드가 되고 후보의 글이 T 칸에 사용자 진술로 들어가며 다른 칸은 비어 있다")
    void manualCandidateWithoutCommitsFillsTaskSlot() {

        Long manual = candidateFixtures.insertManualCandidate(userRepositoryId, "로그인 개선", "로그인 흐름을 맡아 정리했다");

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(manual)));

        assertThat(jdbc.queryForObject("SELECT card_type FROM card WHERE public_id = ?", String.class,
                created.cardIds().get(0))).isEqualTo("QUALITATIVE");
        assertThat(statementsOf(created.cardIds().get(0))).hasSize(1).first().satisfies(row -> {
            assertThat(row.get("star_slot")).isEqualTo("T");
            assertThat(row.get("body")).isEqualTo("로그인 흐름을 맡아 정리했다");
            assertThat(row.get("evidence_type")).isEqualTo("USER_STATED");
        });
        assertThat(candidateFixtures.statusOf(manual)).isEqualTo("USED");

    }

    @Test
    @DisplayName("포함된 커밋이 있는 직접 추가 후보는 기술 카드로 시작하고 T 칸에 후보의 글도 들어간다")
    void manualCandidateWithCommitsIsTechCardWithTaskText() {

        Long manual = candidateFixtures.insertManualCandidate(userRepositoryId, "인덱스 추가", "조회가 느려 인덱스를 넣었다");
        Long commitId = activity.insertCommit(repositoryId, SHA_A, Instant.parse("2026-09-01T01:00:00Z"), 1);
        candidateFixtures.insertCandidateCommit(manual, commitId, true);

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(manual)));

        assertThat(jdbc.queryForObject("SELECT card_type FROM card WHERE public_id = ?", String.class,
                created.cardIds().get(0))).isEqualTo("TECH");
        assertThat(statementsOf(created.cardIds().get(0))).extracting(row -> row.get("star_slot"), row -> row.get("body"))
                .containsExactly(tuple("T", "조회가 느려 인덱스를 넣었다"));

    }

    @Test
    @DisplayName("직접 추가한 커밋이 모두 빠진 후보는 정성 카드가 되고 T 칸은 그대로 채운다")
    void manualCandidateWithOnlyExcludedCommitsIsQualitative() {

        Long manual = candidateFixtures.insertManualCandidate(userRepositoryId, "인덱스 추가", "조회가 느려 인덱스를 넣었다");
        Long commitId = activity.insertCommit(repositoryId, SHA_A, Instant.parse("2026-09-01T01:00:00Z"), 1);
        candidateFixtures.insertCandidateCommit(manual, commitId, false);

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(manual)));

        assertThat(jdbc.queryForObject("SELECT card_type FROM card WHERE public_id = ?", String.class,
                created.cardIds().get(0))).isEqualTo("QUALITATIVE");
        assertThat(statementsOf(created.cardIds().get(0))).hasSize(1);

    }

    @Test
    @DisplayName("AI 가 묶은 후보는 이유 글이 있어도 T 칸을 채우지 않고, 같이 고른 직접 추가 후보만 채운다")
    void groupedCandidateLeavesTaskSlotEmpty() {

        Long grouped = candidateFixtures.insertNewCandidate(userRepositoryId, "묶음 후보");
        Long manual = candidateFixtures.insertManualCandidate(userRepositoryId, "직접 후보", "내가 쓴 글");

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(grouped), publicIdOf(manual)));

        assertThat(statementsOf(created.cardIds().get(0))).isEmpty();
        assertThat(statementsOf(created.cardIds().get(1))).extracting(row -> row.get("body")).containsExactly("내가 쓴 글");

    }

    @Test
    @DisplayName("회상 후보는 ref 가 RECALL 이어도 정성 카드가 되고 T 칸에 답이 앞뒤 공백 그대로 들어가며 다른 칸은 비어 있다")
    void recallCandidateFillsTaskSlotAsReceived() {

        Long recall = candidateFixtures.insertManualCandidate(userRepositoryId, "RECALL", "/a/b/ 작업",
                "  기능을 맡아 만들었어요  ");

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(recall)));

        assertThat(jdbc.queryForObject("SELECT card_type FROM card WHERE public_id = ?", String.class,
                created.cardIds().get(0))).isEqualTo("QUALITATIVE");
        assertThat(statementsOf(created.cardIds().get(0)))
                .extracting(row -> row.get("star_slot"), row -> row.get("body"), row -> row.get("evidence_type"))
                .containsExactly(tuple("T", "  기능을 맡아 만들었어요  ", "USER_STATED"));

    }

    @Test
    @DisplayName("손댄 AI 후보는 AI 가 쓴 이유 글을 사용자 진술로 옮기지 않아 카드에 문장이 하나도 없다")
    void userModifiedAiCandidateLeavesTaskSlotEmpty() {

        Long modified = candidateFixtures.insertUserModifiedCandidate(userRepositoryId, "손댄 묶음 후보");

        CreatedCards created = service.create(userId, userRepositoryId, List.of(publicIdOf(modified)));

        assertThat(statementsOf(created.cardIds().get(0))).isEmpty();

    }

    @Test
    @DisplayName("직접 추가 후보와 함께 고른 후보가 이미 카드에 쓰였으면 T 칸 문장을 포함해 어떤 문장도 남기지 않는다")
    void rollbackLeavesNoTaskStatement() {

        Long manual = candidateFixtures.insertManualCandidate(userRepositoryId, "직접 후보", "내가 쓴 글");
        Long carded = candidateFixtures.insertNewCandidate(userRepositoryId, "이미 카드가 있는 후보");
        candidateFixtures.insertCandidateCard(userId, userRepositoryId, carded, UUID.randomUUID(), false);

        assertThatThrownBy(() -> service.create(userId, userRepositoryId, List.of(publicIdOf(manual), publicIdOf(carded))))
                .isInstanceOf(CandidateAlreadyUsedException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_statement", Integer.class)).isZero();
        assertThat(candidateFixtures.statusOf(manual)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("후보 목록이 null 이어도 500 이 아니라 후보를 고르라는 예외를 던진다")
    void nullCandidateListIsRejected() {

        assertThatThrownBy(() -> service.create(userId, userRepositoryId, null))
                .isInstanceOf(NoCandidatesException.class);

    }

    private List<Map<String, Object>> statementsOf(UUID cardPublicId) {

        return jdbc.queryForList(
                "SELECT s.star_slot, s.body, s.evidence_type FROM card_statement s JOIN card c ON c.id = s.card_id WHERE c.public_id = ? ORDER BY s.star_slot",
                cardPublicId);

    }

    private UUID publicIdOf(Long candidateId) {

        return candidateFixtures.publicIdOf(candidateId);

    }

    private Long idOfTitle(String title) {

        return jdbc.queryForObject("SELECT id FROM candidate WHERE title = ?", Long.class, title);

    }

    private static GroupedCandidate cluster(String ref, String sha) {

        return new GroupedCandidate(CandidateType.COMMIT_CLUSTER, ref, null, List.of(), List.of(), ref + " 작업",
                "같은 날 이어진 작업", 0.5, false, List.of(sha));

    }
}
