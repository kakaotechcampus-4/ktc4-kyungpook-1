package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InterviewTurnPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InterviewTurnRepository turns;

    @Autowired
    EntityManager entityManager;

    private TestFixtures fixtures;
    private InterviewFixtures interviews;
    private Long userId;
    private Long cardId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);

        userId = fixtures.insertUser(1L, "grow22");
        cardId = fixtures.insertCard(UUID.randomUUID(), userId);

    }

    @Test
    @DisplayName("질문의 칸마다 값이 시각까지 저장된 그대로 읽힌다")
    void readsEveryColumn() {

        Long parentId = interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "첫 질문");
        jdbc.update("""
                INSERT INTO interview_turn (card_id, seq, star_slot, question_type, trigger_source, parent_turn_id,
                                            question_text, answer_text, answer_source, outcome, asked_at, answered_at)
                VALUES (?, 2, 'A', 'FOLLOWUP', 'AUTO', ?, '  꼬리 질문  ', '  답  ', 'SELECTED', 'ANSWERED',
                        '2026-10-01T01:02:03.123456Z', '2026-10-02T04:05:06.654321Z')
                """, cardId, parentId);

        InterviewTurn turn = turns.findByCardIdOrderBySeqAsc(cardId).get(1);

        assertThat(turn.getId()).isNotNull();
        assertThat(turn.getCardId()).isEqualTo(cardId);
        assertThat(turn.getSeq()).isEqualTo((short) 2);
        assertThat(turn.getStarSlot()).isEqualTo(StarField.A);
        assertThat(turn.getQuestionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(turn.getTriggerSource()).isEqualTo(TriggerSource.AUTO);
        assertThat(turn.getParentTurnId()).isEqualTo(parentId);
        assertThat(turn.getQuestionText()).isEqualTo("  꼬리 질문  ");
        assertThat(turn.getAnswerText()).isEqualTo("  답  ");
        assertThat(turn.getAnswerSource()).isEqualTo(AnswerSource.SELECTED);
        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);
        assertThat(turn.getAskedAt()).isEqualTo(Instant.parse("2026-10-01T01:02:03.123456Z"));
        assertThat(turn.getAnsweredAt()).isEqualTo(Instant.parse("2026-10-02T04:05:06.654321Z"));
        assertThat(turn.isAnswered()).isTrue();

    }

    @Test
    @DisplayName("답하지 않은 질문은 부모·답·결과·답한 시각이 모두 null 로 읽히고 질문한 시각은 DB 가 정한 값이다")
    void readsOpenTurnWithEmptyColumns() {

        interviews.insertAsked(cardId, 1, "T", "RECALL_AID", "USER", "질문");

        InterviewTurn turn = turns.findByCardIdOrderBySeqAsc(cardId).get(0);

        assertThat(turn.getParentTurnId()).isNull();
        assertThat(turn.getAnswerText()).isNull();
        assertThat(turn.getAnswerSource()).isNull();
        assertThat(turn.getOutcome()).isNull();
        assertThat(turn.getAnsweredAt()).isNull();
        assertThat(turn.getAskedAt()).isNotNull();
        assertThat(turn.isAnswered()).isFalse();

    }

    @Test
    @DisplayName("DB 가 허용하는 칸·질문 종류·누가 물었나·답 출처·결과 값을 모두 읽을 수 있고, 자바 값과 하나도 남거나 모자라지 않는다")
    void readsEveryEnumValue() {

        interviews.insertTurn(cardId, 1, "S", "EVIDENCE_GAP", "USER", "질문", null, null, null);
        interviews.insertTurn(cardId, 2, "T", "FOLLOWUP", "AUTO", "질문", "LATER", null, null);
        interviews.insertTurn(cardId, 3, "A", "RECALL_AID", "USER", "질문", "SKIPPED", null, null);
        interviews.insertTurn(cardId, 4, "R", "EVIDENCE_GAP", "AUTO", "질문", "INSUFFICIENT", "남은 답", "TYPED");
        interviews.insertTurn(cardId, 5, "S", "FOLLOWUP", "USER", "질문", "ANSWERED", "답", "SELECTED");
        interviews.insertTurn(cardId, 6, "R", "EVIDENCE_GAP", "USER", "질문", "MEASURED", "3초에서 0.4초로 줄였다", "TYPED");
        interviews.insertTurn(cardId, 7, "R", "EVIDENCE_GAP", "AUTO", "질문", "NOT_MEASURED", null, null);

        List<InterviewTurn> read = turns.findByCardIdOrderBySeqAsc(cardId);

        assertThat(distinct(read, InterviewTurn::getStarSlot)).isEqualTo(EnumSet.allOf(StarField.class));
        assertThat(distinct(read, InterviewTurn::getQuestionType)).isEqualTo(EnumSet.allOf(QuestionType.class));
        assertThat(distinct(read, InterviewTurn::getTriggerSource)).isEqualTo(EnumSet.allOf(TriggerSource.class));
        assertThat(distinct(read, InterviewTurn::getAnswerSource)).isEqualTo(EnumSet.allOf(AnswerSource.class));
        assertThat(distinct(read, InterviewTurn::getOutcome)).isEqualTo(EnumSet.allOf(TurnOutcome.class));
        assertThat(read.get(0).getOutcome()).isNull();

    }

    @Test
    @DisplayName("카드의 질문만 질문 번호 순서로 읽고, 같은 사용자의 다른 카드 질문은 읽지 않는다")
    void readsTurnsOfOneCardOrderedBySeq() {

        Long otherCardId = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAsked(otherCardId, 2, "S", "EVIDENCE_GAP", "USER", "다른 카드 질문");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "둘째");
        interviews.insertAsked(cardId, 3, "A", "EVIDENCE_GAP", "USER", "셋째");
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "첫째");
        interviews.insertAsked(otherCardId, 1, "S", "EVIDENCE_GAP", "USER", "다른 카드 질문");
        // 인덱스를 타면 정렬이 없어도 질문 번호 순서로 읽혀 ORDER BY 가 빠져도 알 수 없으므로 순차 스캔으로 읽게 한다
        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_bitmapscan = off");
        jdbc.execute("SET LOCAL enable_indexonlyscan = off");

        assertThat(turns.findByCardIdOrderBySeqAsc(cardId))
                .extracting(InterviewTurn::getQuestionText)
                .containsExactly("첫째", "둘째", "셋째");

    }

    @Test
    @DisplayName("질문이 없는 카드는 빈 목록이 읽힌다")
    void readsNothingForCardWithoutTurns() {

        Long otherCardId = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAsked(otherCardId, 1, "S", "EVIDENCE_GAP", "USER", "다른 카드 질문");

        assertThat(turns.findByCardIdOrderBySeqAsc(cardId)).isEmpty();

    }

    @Test
    @DisplayName("카드와 질문 번호로 그 카드의 질문 하나를 읽고, 다른 카드의 같은 번호 질문이나 없는 번호는 읽지 않는다")
    void readsOneTurnByCardAndSeq() {

        Long otherCardId = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAsked(otherCardId, 2, "S", "EVIDENCE_GAP", "USER", "다른 카드의 둘째");
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "첫째");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "둘째");

        assertThat(turns.findByCardIdAndSeq(cardId, (short) 2)).get()
                .extracting(InterviewTurn::getQuestionText).isEqualTo("둘째");
        assertThat(turns.findByCardIdAndSeq(cardId, (short) 3)).isEmpty();
        assertThat(turns.findByCardIdAndSeq(otherCardId, (short) 1)).isEmpty();

    }

    @Test
    @DisplayName("답한 질문은 글·출처·결과·답한 시각이 저장되고 다시 읽어도 같으며 질문 쪽 칸은 그대로다")
    void savesTheAnswerOfATurn() {

        interviews.insertAsked(cardId, 1, "A", "FOLLOWUP", "AUTO", "  이어서 묻는 글  ");
        InterviewTurn turn = turns.findByCardIdAndSeq(cardId, (short) 1).orElseThrow();
        Instant askedAt = turn.getAskedAt();

        turn.answer("  쓴 그대로의 답  ", AnswerSource.TYPED);
        entityManager.flush();
        entityManager.clear();

        InterviewTurn saved = turns.findByCardIdAndSeq(cardId, (short) 1).orElseThrow();
        assertThat(saved.getAnswerText()).isEqualTo("  쓴 그대로의 답  ");
        assertThat(saved.getAnswerSource()).isEqualTo(AnswerSource.TYPED);
        assertThat(saved.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);
        assertThat(saved.getAnsweredAt()).isNotNull();
        assertThat(saved.isAnswered()).isTrue();
        assertThat(saved.getStarSlot()).isEqualTo(StarField.A);
        assertThat(saved.getQuestionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(saved.getTriggerSource()).isEqualTo(TriggerSource.AUTO);
        assertThat(saved.getQuestionText()).isEqualTo("  이어서 묻는 글  ");
        assertThat(saved.getAskedAt()).isEqualTo(askedAt);

    }

    @Test
    @DisplayName("답한 질문이 있는 카드 id 는 물어본 카드 안에서만 한 번씩 돌려주고, 답하지 않았거나 LATER·SKIPPED·INSUFFICIENT 로 끝난 질문은 답으로 보지 않는다")
    void findsCardIdsWithAnAnsweredTurn() {

        Long answeredTwice = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAnswered(answeredTwice, 1, "S", "첫 답", "TYPED");
        interviews.insertAnswered(answeredTwice, 2, "T", "둘째 답", "SELECTED");
        Long answeredOnce = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAnswered(answeredOnce, 1, "S", "답", "TYPED");
        Long unfinished = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAsked(unfinished, 1, "S", "EVIDENCE_GAP", "USER", "열린 질문");
        interviews.insertTurn(unfinished, 2, "T", "EVIDENCE_GAP", "USER", "질문", "LATER", null, null);
        interviews.insertTurn(unfinished, 3, "A", "EVIDENCE_GAP", "USER", "질문", "SKIPPED", null, null);
        interviews.insertTurn(unfinished, 4, "R", "EVIDENCE_GAP", "USER", "질문", "INSUFFICIENT", "남은 답", "TYPED");
        Long notAsked = fixtures.insertCard(UUID.randomUUID(), userId);
        interviews.insertAnswered(notAsked, 1, "S", "묻지 않은 카드의 답", "TYPED");

        List<Long> found = turns.findCardIdsWithAnswer(List.of(answeredTwice, answeredOnce, unfinished, cardId));

        assertThat(found).containsExactlyInAnyOrder(answeredTwice, answeredOnce);

    }

    @Test
    @DisplayName("카드 id 를 하나도 주지 않으면 빈 목록을 돌려준다")
    void findsNothingForNoCardIds() {

        assertThat(turns.findCardIdsWithAnswer(List.of())).isEmpty();

    }

    private <T> Set<T> distinct(List<InterviewTurn> read, Function<InterviewTurn, T> getter) {

        return read.stream().map(getter).filter(value -> value != null).collect(Collectors.toSet());

    }
}
