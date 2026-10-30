package com.gitory.backend.coverletter.domain;

import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CoverLetterPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CoverLetterRepository letters;

    @Autowired
    CoverLetterQuestionRepository questions;

    @Autowired
    CardMaterialAnswerRepository remembered;

    private TestFixtures fixtures;
    private Long userId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("새 자소서는 카드 고르기를 줄 서서 기다리고, 공백뿐인 메모는 없음으로 저장된다")
    void newCoverLetterWaitsForCardAssignment() {

        CoverLetter saved = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", "  ", "key-1"));

        CoverLetter found = letters.findById(saved.getId()).orElseThrow();
        assertThat(found.getStage()).isEqualTo(CoverLetterStage.ASSIGNING);
        assertThat(found.getRunningSince()).isNull();
        assertThat(found.getMemo()).isNull();
        assertThat(found.getFailedStep()).isNull();
        assertThat(found.getAssignSteps()).isEmpty();
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());

    }

    @Test
    @DisplayName("JSONB 칸(과정·견준 카드·질문·답·문장·표시·빈칸)은 칸 이름 그대로 다시 읽힌다")
    void jsonColumnsAreReadBackAsRecords() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        CoverLetterQuestion question = questions.saveAndFlush(CoverLetterQuestion.of(letter.getId(), 1, "협업 경험", 700));
        jdbc.update("""
                UPDATE cover_letter SET assign_steps = ?::jsonb, draft_steps = ?::jsonb WHERE id = ?
                """,
                "[{\"seq\":1,\"actor\":\"SUB_AGENT\",\"kind\":\"PICKED\",\"questionSeq\":1,\"cardId\":7,\"reason\":\"맞아서\"}]",
                "[{\"seq\":1,\"actor\":\"CODE\",\"kind\":\"CHECK_FAILED\",\"questionSeq\":1,\"code\":\"UNSOURCED_NUMBER\","
                        + "\"value\":\"30%\",\"sentenceSeq\":null,\"action\":null,\"reason\":null}]",
                letter.getId());
        jdbc.update("""
                UPDATE cover_letter_question SET considered = ?::jsonb, asks = ?::jsonb, answers = ?::jsonb,
                       sentences = ?::jsonb, marks = ?::jsonb, blanks = ?::jsonb WHERE id = ?
                """,
                "[{\"cardId\":7,\"note\":\"결과가 분명함\"}]",
                "[{\"item\":\"LEARNING\",\"question\":\"무엇을 배웠나요?\",\"required\":true,\"templates\":[\"___ 을 배웠다\"]}]",
                "[{\"item\":\"LEARNING\",\"text\":\"재 보고 고치기\",\"skipped\":false,\"reused\":true}]",
                "[{\"seq\":1,\"text\":\"첫 문장입니다.\"}]",
                "[{\"sentenceSeq\":1,\"code\":\"UNSOURCED_NUMBER\",\"value\":\"30%\"}]",
                "[\"RESULT\"]",
                question.getId());

        CoverLetter foundLetter = letters.findById(letter.getId()).orElseThrow();
        CoverLetterQuestion found = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        assertThat(foundLetter.getAssignSteps()).containsExactly(new AssignStep(1, "SUB_AGENT", "PICKED", 1, 7L, "맞아서"));
        assertThat(foundLetter.getDraftSteps()).containsExactly(
                new DraftStep(1, "CODE", "CHECK_FAILED", 1, "UNSOURCED_NUMBER", "30%", null, null, null));
        assertThat(found.getConsidered()).containsExactly(new ConsideredCard(7L, "결과가 분명함"));
        assertThat(found.getAsks()).containsExactly(new MaterialAsk(MaterialItem.LEARNING, "무엇을 배웠나요?", true, List.of("___ 을 배웠다")));
        assertThat(found.getAnswers()).containsExactly(new MaterialAnswer(MaterialItem.LEARNING, "재 보고 고치기", false, true));
        assertThat(found.getSentences()).containsExactly(new DraftSentence(1, "첫 문장입니다."));
        assertThat(found.getMarks()).containsExactly(new DraftMark(1, "UNSOURCED_NUMBER", "30%"));
        assertThat(found.getBlanks()).containsExactly(MaterialItem.RESULT);

    }

    @Test
    @DisplayName("같은 사용자가 같은 키로 두 번 만들 수 없고, 키가 없으면 여러 편을 만들 수 있다")
    void idempotencyKeyIsUniquePerUser() {

        letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, "key-1"));
        Long otherUser = fixtures.insertUser(2L, "taehun0208");
        letters.saveAndFlush(CoverLetter.start(otherUser, "백엔드 개발", null, "key-1"));

        assertThatThrownBy(() -> letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, "key-1")))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("한 자소서에 같은 순번 문항은 하나뿐이다")
    void questionSeqIsUniquePerCoverLetter() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        questions.saveAndFlush(CoverLetterQuestion.of(letter.getId(), 1, "협업 경험", 700));

        assertThatThrownBy(() -> questions.saveAndFlush(CoverLetterQuestion.of(letter.getId(), 1, "다른 문항", 700)))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("실패한 단계는 FAILED 일 때만 있고, FAILED 면 반드시 있다")
    void failedStepGoesWithFailedStage() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));

        assertThatThrownBy(() -> jdbc.update("UPDATE cover_letter SET failed_step = 'ASSIGN' WHERE id = ?", letter.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE cover_letter SET stage = 'FAILED' WHERE id = ?", letter.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE cover_letter SET stage = 'FAILED', failed_step = 'ASSIGN' WHERE id = ?", letter.getId());

    }

    @Test
    @DisplayName("카드를 지우면 문항의 카드 칸만 비고 문항은 남으며, 그 카드에 기억한 답은 함께 지워진다")
    void deletingCardEmptiesQuestionSlotsAndDropsRememberedAnswers() {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        CoverLetterQuestion question = questions.saveAndFlush(CoverLetterQuestion.of(letter.getId(), 1, "협업 경험", 700));
        jdbc.update("UPDATE cover_letter_question SET primary_card_id = ?, supporting_card_id = ? WHERE id = ?",
                card, card, question.getId());
        remembered.saveAndFlush(CardMaterialAnswer.of(card, MaterialItem.LEARNING, "재 보고 고치기"));

        jdbc.update("DELETE FROM card WHERE id = ?", card);

        CoverLetterQuestion found = questions.findById(question.getId()).orElseThrow();
        assertThat(found.getPrimaryCardId()).isNull();
        assertThat(found.getSupportingCardId()).isNull();
        assertThat(remembered.findByCardIdIn(List.of(card))).isEmpty();

    }

    @Test
    @DisplayName("사용자를 지우면 자소서와 문항이 함께 지워진다")
    void deletingUserDropsCoverLettersAndQuestions() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드 개발", null, null));
        questions.saveAndFlush(CoverLetterQuestion.of(letter.getId(), 1, "협업 경험", 700));

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter_question", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter", Integer.class)).isZero();

    }

    @Test
    @DisplayName("카드마다 같은 항목의 기억한 답은 하나뿐이고, 직무 연결·지원 이유는 기억하지 않는다")
    void remembersOneAnswerPerCardItemOnlyForExperienceItems() {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        remembered.saveAndFlush(CardMaterialAnswer.of(card, MaterialItem.LEARNING, "처음 답"));

        assertThatThrownBy(() -> remembered.saveAndFlush(CardMaterialAnswer.of(card, MaterialItem.LEARNING, "다른 답")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> CardMaterialAnswer.of(card, MaterialItem.JOB_LINK, "직무와 이어짐"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO card_material_answer (card_id, item, text, updated_at) VALUES (?, 'REASON', '이유', now())", card))
                .isInstanceOf(DataIntegrityViolationException.class);

    }
}
