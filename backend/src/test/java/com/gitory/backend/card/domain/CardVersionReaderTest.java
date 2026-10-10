package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(CardVersionReader.class)
class CardVersionReaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardVersionReader reader;

    @Autowired
    CardRepository cards;

    @Autowired
    JdbcTemplate jdbc;

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
    @DisplayName("버전 하나를 읽으면 그 버전의 칸만 돌려주고, 행이 없는 칸은 글 null·EMPTY, LOW 는 NEEDS_REVIEW, MEDIUM·HIGH·확신도 없음은 FILLED 이며 커밋은 연결한 순서다")
    void readsOneVersion() {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "COMMIT", "LOW");
        fixtures.insertStatement(cardId, 1, "T", "과제", "COMMIT", "MEDIUM");
        Long action = fixtures.insertStatement(cardId, 1, "A", "행동", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(action, secondCommit);
        fixtures.insertStatementEvidence(action, firstCommit);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "다시 쓴 상황", "USER_STATED", null);

        VersionSlots first = reader.read(cardId, (short) 1);
        VersionSlots second = reader.read(cardId, (short) 2);

        assertThat(first.versionNo()).isEqualTo((short) 1);
        assertThat(first.slots()).containsOnlyKeys(StarField.S, StarField.T, StarField.A);
        assertThat(first.slots().get(StarField.A)).isEqualTo(new SlotContent(StarField.A, "행동", EvidenceType.COMMIT,
                Confidence.HIGH, null, List.of(secondCommit, firstCommit)));
        assertThat(first.stateOf(StarField.S)).isEqualTo(StarFieldState.NEEDS_REVIEW);
        assertThat(first.stateOf(StarField.T)).isEqualTo(StarFieldState.FILLED);
        assertThat(first.stateOf(StarField.A)).isEqualTo(StarFieldState.FILLED);
        assertThat(first.stateOf(StarField.R)).isEqualTo(StarFieldState.EMPTY);
        assertThat(first.textOf(StarField.R)).isNull();

        assertThat(second.versionNo()).isEqualTo((short) 2);
        assertThat(second.slots()).containsOnlyKeys(StarField.S);
        assertThat(second.textOf(StarField.S)).isEqualTo("다시 쓴 상황");
        assertThat(second.stateOf(StarField.S)).isEqualTo(StarFieldState.FILLED);
        assertThat(second.stateOf(StarField.T)).isEqualTo(StarFieldState.EMPTY);

    }

    @Test
    @DisplayName("여러 카드를 읽으면 카드마다 현재 버전의 칸만 돌려주고 옛 버전 문장은 섞지 않으며, 문장이 없는 카드도 빈 칸으로 넣는다")
    void readsCurrentVersionOfEachCard() {

        Long edited = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(edited, 1, "S", "옛 상황", "USER_STATED", null);
        fixtures.insertStatement(edited, 1, "T", "옛 과제", "USER_STATED", null);
        fixtures.insertCardVersion(edited, 2, "USER_EDIT", false);
        Long situation = fixtures.insertStatement(edited, 2, "S", "새 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long empty = fixtures.insertCard(UUID.randomUUID(), userId);

        Map<Long, VersionSlots> read = reader.readCurrent(cards.findAllById(List.of(edited, empty)));

        assertThat(read).containsOnlyKeys(edited, empty);
        assertThat(read.get(edited).versionNo()).isEqualTo((short) 2);
        assertThat(read.get(edited).slots()).containsOnlyKeys(StarField.S);
        assertThat(read.get(edited).slots().get(StarField.S)).isEqualTo(new SlotContent(StarField.S, "새 상황",
                EvidenceType.COMMIT, Confidence.HIGH, null, List.of(firstCommit)));
        assertThat(read.get(empty).versionNo()).isEqualTo((short) 1);
        assertThat(read.get(empty).slots()).isEmpty();

    }
}
