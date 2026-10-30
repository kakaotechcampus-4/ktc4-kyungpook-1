package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsPort;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
import com.gitory.backend.agent.port.CoverLetterQuestions;
import com.gitory.backend.agent.port.CoverLetterQuestionsPort;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
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
class CoverLetterRunnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CoverLetterRunner runner;

    @Autowired
    CoverLetterRepository letters;

    @Autowired
    CoverLetterQuestionRepository questions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CardMaterialAnswerRepository answers;

    @MockitoBean
    CoverLetterAssignmentPort assignment;

    @MockitoBean
    CoverLetterQuestionsPort questionsPort;

    @MockitoBean
    CoverLetterDraftsPort draftsPort;

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
    @DisplayName("질문 만들기를 기다리는 자소서도 집어, 배정된 카드 전문과 중심 카드의 지난 답을 AI 에 보내고, 받은 질문 뒤에 AI 가 묻지 않은 지난 답을 붙여 저장한다")
    void savesAsksWithRememberedAnswersOfPrimaryCard() {

        Long primary = confirmedCard("배포 시간 단축", "S", "A");
        Long supporting = confirmedCard("동아리 회계", "R");
        Long deletedPrimary = confirmedCard("지운 카드", "A");
        Long leftSupporting = confirmedCard("남은 보조 카드", "A");
        answers.save(CardMaterialAnswer.of(primary, MaterialItem.LEARNING, "작게 나눠 배포하게 됐다"));
        answers.save(CardMaterialAnswer.of(supporting, MaterialItem.TRIGGER, "보조 카드의 계기"));
        CoverLetter letter = askingLetter(new Pick(QuestionTheme.COLLABORATION, primary, supporting),
                new Pick(QuestionTheme.FAILURE, deletedPrimary, leftSupporting),
                new Pick(QuestionTheme.GROWTH, primary, null));
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deletedPrimary);
        AtomicReference<CoverLetterQuestionsRequest> sent = new AtomicReference<>();
        given(questionsPort.ask(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return new CoverLetterQuestions(List.of(
                    new CoverLetterQuestions.Question(1, List.of("RESULT"), List.of(
                            new CoverLetterQuestions.Ask("RESULT", "배포가 얼마나 빨라졌나요?",
                                    List.of("배포가 ___ 빨라졌다")),
                            new CoverLetterQuestions.Ask("TRIGGER", "왜 고치기로 했나요?", List.of()))),
                    new CoverLetterQuestions.Question(2, List.of(), List.of(
                            new CoverLetterQuestions.Ask("SITUATION", "어떤 상황이었나요?", List.of()))),
                    new CoverLetterQuestions.Question(3, List.of(), List.of(
                            new CoverLetterQuestions.Ask("LEARNING", "무엇을 배웠나요?", List.of())))));
        });

        runner.run(runner.claimNext().orElseThrow());

        CoverLetterQuestionsRequest.Question first = sent.get().questions().getFirst();
        assertThat(first.questionTheme()).isEqualTo("COLLABORATION");
        assertThat(first.primaryCard().cardId()).isEqualTo(primary);
        assertThat(first.primaryCard().statements()).containsExactly(
                new CoverLetterQuestionsRequest.Statement("S", "S 문장"),
                new CoverLetterQuestionsRequest.Statement("A", "A 문장"));
        assertThat(first.supportingCard().cardId()).isEqualTo(supporting);
        assertThat(first.knownAnswers()).containsExactly(
                new CoverLetterQuestionsRequest.KnownAnswer("LEARNING", "작게 나눠 배포하게 됐다", primary));
        CoverLetterQuestionsRequest.Question second = sent.get().questions().get(1);
        assertThat(second.primaryCard()).isNull();
        assertThat(second.supportingCard()).isNull();
        assertThat(second.knownAnswers()).isEmpty();

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.ASKED);
        assertThat(saved.getRunningSince()).isNull();
        List<CoverLetterQuestion> stored = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        assertThat(stored.getFirst().getAsks()).containsExactly(
                new MaterialAsk(MaterialItem.RESULT, "배포가 얼마나 빨라졌나요?", true, List.of("배포가 ___ 빨라졌다")),
                new MaterialAsk(MaterialItem.TRIGGER, "왜 고치기로 했나요?", false, List.of()),
                new MaterialAsk(MaterialItem.LEARNING, null, false, List.of()));
        assertThat(stored.getFirst().getAnswers()).containsExactly(
                new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 배포하게 됐다", false, true));
        assertThat(stored.get(1).getAsks()).containsExactly(
                new MaterialAsk(MaterialItem.SITUATION, "어떤 상황이었나요?", false, List.of()));
        assertThat(stored.get(1).getAnswers()).isEmpty();
        assertThat(stored.get(2).getAsks()).containsExactly(
                new MaterialAsk(MaterialItem.LEARNING, "무엇을 배웠나요?", false, List.of()));
        assertThat(stored.get(2).getAnswers()).containsExactly(
                new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 배포하게 됐다", false, true));

    }

    @Test
    @DisplayName("카드 문장은 AI 가 카드마다 받는 20개까지만 보낸다")
    void sendsAtMostTwentyStatementsPerCard() {

        Long card = confirmedCard("문장이 많은 카드");
        for (int seq = 1; seq <= 21; seq++) {
            jdbc.update("INSERT INTO card_statement (card_id, version_no, star_slot, seq, body, evidence_type) "
                    + "VALUES (?, 1, 'A', ?, ?, 'USER_STATED')", card, seq, "행동 " + seq);
        }
        askingLetter(new Pick(QuestionTheme.COLLABORATION, card, null));
        AtomicReference<CoverLetterQuestionsRequest> sent = new AtomicReference<>();
        given(questionsPort.ask(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return new CoverLetterQuestions(List.of(new CoverLetterQuestions.Question(1, List.of(), List.of())));
        });

        runner.run(runner.claimNext().orElseThrow());

        assertThat(sent.get().questions().getFirst().primaryCard().statements()).hasSize(20);

    }

    @Test
    @DisplayName("질문 만들기 AI 호출이 실패하면 질문 단계 실패로 끝낸다")
    void failsAskStepWhenAiFails() {

        Long card = confirmedCard("배포 시간 단축", "A");
        CoverLetter letter = askingLetter(new Pick(QuestionTheme.COLLABORATION, card, null));
        given(questionsPort.ask(any())).willThrow(new AgentUnavailableException("AI 실패", true, false, null));

        runner.run(runner.claimNext().orElseThrow());

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.FAILED);
        assertThat(saved.getFailedStep()).isEqualTo(CoverLetterStep.ASK);
        assertThat(saved.getErrorCode()).isEqualTo(CoverLetterError.AI_UNAVAILABLE.name());
        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getAsks()).isEmpty();

    }

    @Test
    @DisplayName("초안 쓰기를 기다리는 자소서도 집어, 넘기지 않은 답과 꼭 필요한데 받지 못한 항목을 AI 에 보내고, 받은 초안과 쓰기 과정을 저장한다")
    void savesDraftsWithWrittenAnswersAndMissingItems() {

        Long card = confirmedCard("배포 시간 단축", "A");
        CoverLetter letter = draftingLetter(card,
                List.of(new MaterialAsk(MaterialItem.RESULT, "결과는요?", true, List.of()),
                        new MaterialAsk(MaterialItem.JOB_LINK, "직무와 어떻게 이어지나요?", true, List.of()),
                        new MaterialAsk(MaterialItem.REASON, "왜 지원했나요?", false, List.of()),
                        new MaterialAsk(MaterialItem.TRIGGER, "계기는요?", false, List.of()),
                        new MaterialAsk(MaterialItem.LEARNING, null, false, List.of())),
                List.of(new MaterialAnswer(MaterialItem.RESULT, "배포가 3분 빨라졌다", false, false),
                        new MaterialAnswer(MaterialItem.JOB_LINK, null, true, false),
                        new MaterialAnswer(MaterialItem.REASON, "결제를 다뤄 보고 싶다", false, false),
                        new MaterialAnswer(MaterialItem.TRIGGER, null, true, false),
                        new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 배포하게 됐다", false, true)));
        AtomicReference<CoverLetterDraftsRequest> sent = new AtomicReference<>();
        given(draftsPort.write(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return new CoverLetterDrafts(List.of(new CoverLetterDrafts.Draft(1, "배포를 고쳤다. [직무 연결: 지원 직무와 이어지는 점을 적어 주세요]",
                            List.of(new CoverLetterDrafts.Sentence(1, "배포를 고쳤다.")), 8, 0,
                            List.of(new CoverLetterDrafts.Mark(1, "UNSOURCED_TERM", "CI")), List.of("JOB_LINK"), "LLM")),
                    List.of(new CoverLetterDrafts.Step(1, "SUB_AGENT", "WROTE", 1, null, "8", null, null, null)));
        });

        runner.run(runner.claimNext().orElseThrow());

        CoverLetterDraftsRequest.Question question = sent.get().questions().getFirst();
        assertThat(question.primaryCard().cardId()).isEqualTo(card);
        assertThat(question.answers()).containsExactly(
                new CoverLetterQuestionsRequest.KnownAnswer("RESULT", "배포가 3분 빨라졌다", card),
                new CoverLetterQuestionsRequest.KnownAnswer("REASON", "결제를 다뤄 보고 싶다", null),
                new CoverLetterQuestionsRequest.KnownAnswer("LEARNING", "작게 나눠 배포하게 됐다", card));
        assertThat(question.missingItems()).containsExactly("JOB_LINK");

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.DRAFTED);
        assertThat(saved.getRunningSince()).isNull();
        assertThat(saved.getDraftSteps()).containsExactly(new DraftStep(1, "SUB_AGENT", "WROTE", 1, null, "8", null,
                null, null));
        CoverLetterQuestion stored = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        assertThat(stored).extracting(CoverLetterQuestion::getAiText, CoverLetterQuestion::getDraftText,
                        CoverLetterQuestion::isEdited, CoverLetterQuestion::getCharCount, CoverLetterQuestion::getOverBy,
                        CoverLetterQuestion::getBlanks, CoverLetterQuestion::getDraftSource)
                .containsExactly("배포를 고쳤다. [직무 연결: 지원 직무와 이어지는 점을 적어 주세요]",
                        "배포를 고쳤다. [직무 연결: 지원 직무와 이어지는 점을 적어 주세요]", false, 8, 0,
                        List.of(MaterialItem.JOB_LINK), DraftSource.LLM);
        assertThat(stored.getSentences()).containsExactly(new DraftSentence(1, "배포를 고쳤다."));
        assertThat(stored.getMarks()).containsExactly(new DraftMark(1, "UNSOURCED_TERM", "CI"));

    }

    @Test
    @DisplayName("초안 쓰기 AI 호출이 실패하면 초안 단계 실패로 끝낸다")
    void failsDraftStepWhenAiFails() {

        CoverLetter letter = draftingLetter(confirmedCard("배포 시간 단축", "A"), List.of(), List.of());
        given(draftsPort.write(any())).willThrow(new AgentUnavailableException("AI 실패", false, true, null));

        runner.run(runner.claimNext().orElseThrow());

        CoverLetter saved = letters.findById(letter.getId()).orElseThrow();
        assertThat(saved.getStage()).isEqualTo(CoverLetterStage.FAILED);
        assertThat(saved.getFailedStep()).isEqualTo(CoverLetterStep.DRAFT);
        assertThat(saved.getErrorCode()).isEqualTo(CoverLetterError.INTERNAL_ERROR.name());

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 자소서를 지웠으면 늦게 온 초안은 버린다")
    void dropsDraftsOfLetterDeletedWhileWaiting() {

        CoverLetter letter = draftingLetter(confirmedCard("배포 시간 단축", "A"), List.of(), List.of());
        given(draftsPort.write(any())).willAnswer(invocation -> {
            jdbc.update("DELETE FROM cover_letter WHERE id = ?", letter.getId());
            return new CoverLetterDrafts(List.of(new CoverLetterDrafts.Draft(1, "초안", List.of(), 2, 0, List.of(),
                    List.of(), "LLM")), List.of());
        });

        runner.run(runner.claimNext().orElseThrow());

        assertThat(letters.findById(letter.getId())).isEmpty();
        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId())).isEmpty();

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

    private CoverLetter askingLetter(Pick... picks) {

        CoverLetter letter = letters.save(CoverLetter.start(userId, "백엔드 개발", null, null));
        for (int i = 0; i < picks.length; i++) {
            CoverLetterQuestion question = CoverLetterQuestion.of(letter.getId(), i + 1, "문항 " + (i + 1), 700);
            question.assign(picks[i].theme(), picks[i].primary(), picks[i].supporting(), AssignedBy.SUB_AGENT, "이유",
                    List.of());
            questions.save(question);
        }
        letter.claim();
        letter.assigned(List.of());
        letter.startAsking();
        return letters.save(letter);

    }

    private CoverLetter draftingLetter(Long card, List<MaterialAsk> asks, List<MaterialAnswer> answers) {

        CoverLetter letter = askingLetter(new Pick(QuestionTheme.COLLABORATION, card, null));
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.asked(asks, answers);
        questions.save(question);
        letter.claim();
        letter.asked();
        letter.startDrafting();
        return letters.save(letter);

    }

    private record Pick(QuestionTheme theme, Long primary, Long supporting) {
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
