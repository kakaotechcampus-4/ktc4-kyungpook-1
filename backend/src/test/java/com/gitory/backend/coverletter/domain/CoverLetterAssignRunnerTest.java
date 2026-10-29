package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class CoverLetterAssignRunnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CoverLetterAssignRunner runner;

    @Autowired
    CoverLetterRepository letters;

    @Autowired
    CoverLetterQuestionRepository questions;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CoverLetterAssignmentPort assignment;

    private TestFixtures fixtures;
    private Long userId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("카드 고르기를 기다리는 자소서를 먼저 온 순서로 하나씩 집고, 집은 자소서와 다른 단계의 자소서는 다시 집지 않는다")
    void claimsWaitingLettersOneAtATime() {

        CoverLetter first = waitingLetter("문항");
        CoverLetter second = waitingLetter("문항");
        CoverLetter assigned = waitingLetter("문항");
        assigned.claim();
        assigned.assigned(List.of());
        letters.save(assigned);

        assertThat(runner.claimNext()).get().extracting(CoverLetter::getId).isEqualTo(first.getId());
        assertThat(runner.claimNext()).get().extracting(CoverLetter::getId).isEqualTo(second.getId());
        assertThat(runner.claimNext()).isEmpty();
        assertThat(letters.findById(first.getId()).orElseThrow().getRunningSince()).isNotNull();

    }

    @Test
    @DisplayName("집은 자소서의 문항·최근 확정 카드 요약을 AI 에 보내고, 받은 배정과 과정을 문항에 저장한 뒤 카드 고르기를 끝낸다")
    void savesAssignmentAndSendsCardSummaries() {

        Long older = confirmedCard("동아리 회계", "S");
        Long newer = confirmedCard("배포 시간 단축", "S", "A", "R");
        jdbc.update("UPDATE card SET confirmed_at = now() - interval '1 day' WHERE id = ?", older);
        CoverLetter letter = waitingLetter("협업 경험을 쓰세요", "실패 경험을 쓰세요");
        AtomicReference<CoverLetterAssignmentRequest> sent = new AtomicReference<>();
        AtomicReference<Long> markedLetter = new AtomicReference<>();
        given(assignment.assign(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            markedLetter.set(AiCallContext.coverLetterId());
            return new CoverLetterAssignment(List.of(
                    new CoverLetterAssignment.Question(1, "COLLABORATION", newer, older, "함께 고친 경험",
                            List.of(new CoverLetterAssignment.Considered(older, "협업이 덜 드러남")), "SUPERVISOR"),
                    new CoverLetterAssignment.Question(2, null, null, null, null, List.of(), "FAILED")),
                    List.of(new CoverLetterAssignment.Step(1, "SUB_AGENT", "PICKED", 1, newer, "함께 고친 경험")));
        });

        runner.run(runner.claimNext().orElseThrow());

        assertThat(sent.get().coverLetterId()).isEqualTo(letter.getId());
        assertThat(markedLetter.get()).isEqualTo(letter.getId());
        assertThat(sent.get().questions()).extracting(CoverLetterAssignmentRequest.Question::questionId,
                CoverLetterAssignmentRequest.Question::text).containsExactly(tuple(1, "협업 경험을 쓰세요"),
                tuple(2, "실패 경험을 쓰세요"));
        assertThat(sent.get().cards()).extracting(CoverLetterAssignmentRequest.Card::cardId,
                CoverLetterAssignmentRequest.Card::summary).containsExactly(tuple(newer, "A 문장 R 문장"),
                tuple(older, "S 문장"));

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.ASSIGNED);
        assertThat(saved.getRunningSince()).isNull();
        assertThat(saved.getAssignSteps()).containsExactly(new AssignStep(1, "SUB_AGENT", "PICKED", 1, newer, "함께 고친 경험"));
        List<CoverLetterQuestion> stored = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        assertThat(stored.getFirst()).extracting(CoverLetterQuestion::getTheme, CoverLetterQuestion::getPrimaryCardId,
                        CoverLetterQuestion::getSupportingCardId, CoverLetterQuestion::getAssignedBy,
                        CoverLetterQuestion::getPickReason, CoverLetterQuestion::getConsidered)
                .containsExactly(QuestionTheme.COLLABORATION, newer, older, AssignedBy.SUPERVISOR, "함께 고친 경험",
                        List.of(new ConsideredCard(older, "협업이 덜 드러남")));
        assertThat(stored.get(1)).extracting(CoverLetterQuestion::getTheme, CoverLetterQuestion::getPrimaryCardId,
                CoverLetterQuestion::getAssignedBy).containsExactly(null, null, AssignedBy.FAILED);

    }

    @ParameterizedTest
    @CsvSource({"true, false, AI_UNAVAILABLE", "false, true, INTERNAL_ERROR"})
    @DisplayName("AI 호출이 실패하면 카드 고르기 단계 실패로 끝내고, 잠시 못 한 실패만 다시 시도할 수 있는 코드로 남긴다")
    void failsAssignStepByAiFailureKind(boolean retryable, boolean invalidResponse, CoverLetterError expected) {

        CoverLetter letter = waitingLetter("문항");
        given(assignment.assign(any()))
                .willThrow(new AgentUnavailableException("AI 실패", retryable, invalidResponse, null));

        runner.run(runner.claimNext().orElseThrow());

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.FAILED);
        assertThat(saved.getFailedStep()).isEqualTo(CoverLetterStep.ASSIGN);
        assertThat(saved.getErrorCode()).isEqualTo(expected.name());
        assertThat(saved.getRunningSince()).isNull();

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 자소서가 실패로 끝났으면 늦게 온 결과는 버린다")
    void dropsResultWhenLetterChangedWhileWaiting() {

        Long card = confirmedCard("배포 시간 단축", "A");
        CoverLetter letter = waitingLetter("문항");
        given(assignment.assign(any())).willAnswer(invocation -> {
            jdbc.update("UPDATE cover_letter SET stage = 'FAILED', failed_step = 'ASSIGN', error_code = 'AI_UNAVAILABLE', "
                    + "running_since = NULL WHERE id = ?", letter.getId());
            return new CoverLetterAssignment(List.of(new CoverLetterAssignment.Question(1, "COLLABORATION", card,
                    null, "이유", List.of(), "SUB_AGENT")), List.of());
        });

        runner.run(runner.claimNext().orElseThrow());

        assertThat(letters.findById(letter.getId()).orElseThrow().getStage()).isEqualTo(CoverLetterStage.FAILED);
        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPrimaryCardId()).isNull();

    }

    @Test
    @DisplayName("집은 지 5분이 지난 단계만 다시 시도할 수 있는 실패로 끝낸다")
    void failsStepsStuckForFiveMinutes() {

        CoverLetter stuck = waitingLetter("문항");
        CoverLetter recent = waitingLetter("문항");
        jdbc.update("UPDATE cover_letter SET running_since = now() - interval '6 minutes' WHERE id = ?", stuck.getId());
        jdbc.update("UPDATE cover_letter SET running_since = now() - interval '4 minutes' WHERE id = ?", recent.getId());

        runner.failStuck();

        CoverLetter failed = letters.findById(stuck.getId()).orElseThrow();
        assertThat(failed.getStage()).isEqualTo(CoverLetterStage.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(CoverLetterError.AI_UNAVAILABLE.name());
        assertThat(letters.findById(recent.getId()).orElseThrow().getStage()).isEqualTo(CoverLetterStage.ASSIGNING);

    }

    @Test
    @DisplayName("서버가 다시 뜨면 돌던 단계를 줄 세워 다시 집을 수 있게 한다")
    void requeuesRunningLettersAfterRestart() {

        CoverLetter letter = waitingLetter("문항");
        runner.claimNext().orElseThrow();

        runner.requeueRunning();

        assertThat(letters.findById(letter.getId()).orElseThrow().getRunningSince()).isNull();
        assertThat(runner.claimNext()).get().extracting(CoverLetter::getId).isEqualTo(letter.getId());

    }

    private CoverLetter waitingLetter(String... texts) {

        CoverLetter letter = letters.save(CoverLetter.start(userId, "백엔드 개발", null, null));
        for (int i = 0; i < texts.length; i++) {
            questions.save(CoverLetterQuestion.of(letter.getId(), i + 1, texts[i], 700));
        }
        return letter;

    }

    private Long confirmedCard(String title, String... slots) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        for (String slot : slots) {
            fixtures.insertStatement(card, 1, slot, slot + " 문장", "USER_STATED", null);
        }
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET title = ? WHERE id = ?", title, card);
        return card;

    }
}
