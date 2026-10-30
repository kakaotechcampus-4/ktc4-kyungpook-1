package com.gitory.backend.coverletter.api;

import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.AssignStep;
import com.gitory.backend.coverletter.domain.AssignedBy;
import com.gitory.backend.coverletter.domain.CardMaterialAnswer;
import com.gitory.backend.coverletter.domain.ConsideredCard;
import com.gitory.backend.coverletter.domain.CoverLetter;
import com.gitory.backend.coverletter.domain.CoverLetterError;
import com.gitory.backend.coverletter.domain.CoverLetterQuestion;
import com.gitory.backend.coverletter.domain.DraftMark;
import com.gitory.backend.coverletter.domain.DraftSentence;
import com.gitory.backend.coverletter.domain.DraftSource;
import com.gitory.backend.coverletter.domain.DraftStep;
import com.gitory.backend.coverletter.domain.MaterialAnswer;
import com.gitory.backend.coverletter.domain.MaterialAsk;
import com.gitory.backend.coverletter.domain.MaterialItem;
import com.gitory.backend.coverletter.domain.QuestionTheme;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class CoverLetterApiTest {

    private static final String LEARNING_BLANK = "[배운 점: 배운 점을 적어 주세요]";

    private static final String BODY = """
            {"jobRole": "백엔드 개발", "memo": "결제 서비스를 만들고 싶다",
             "questions": [{"text": "협업 경험을 쓰세요", "charLimit": 500}, {"text": "실패 경험을 쓰세요", "charLimit": null}]}
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CoverLetterRepository letters;

    @Autowired
    CoverLetterQuestionRepository questions;

    @Autowired
    CardMaterialAnswerRepository answers;

    @MockitoBean
    UserRateLimiter rateLimiter;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "taehun0208");

    }

    @Test
    @DisplayName("만들면 문항을 순서대로 저장하고, 글자 수가 비면 700 으로 두고, 카드 고르기를 기다리며 호출 제한을 한 번 센다")
    void createsLetterWaitingForCardAssignment() throws Exception {

        confirmedCard(myUserId, "배포 시간 단축", "A");

        create(BODY, "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ASSIGNING"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1500))
                .andExpect(jsonPath("$.data.retryable").value(nullValue()))
                .andExpect(jsonPath("$.data.jobRole").value("백엔드 개발"))
                .andExpect(jsonPath("$.data.questions[0].seq").value(1))
                .andExpect(jsonPath("$.data.questions[0].charLimit").value(500))
                .andExpect(jsonPath("$.data.questions[1].text").value("실패 경험을 쓰세요"))
                .andExpect(jsonPath("$.data.questions[1].charLimit").value(700))
                .andExpect(jsonPath("$.data.questions[1].primaryCard").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[1].primaryCardDeleted").value(false))
                .andExpect(jsonPath("$.data.steps.assign").isEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter WHERE running_since IS NULL", Integer.class))
                .isEqualTo(1);
        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER, myUserId, 1);

    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("직무·메모·문항·글자 수·요청 키가 규칙을 벗어나면 400 이고 아무것도 만들지 않는다")
    void rejectsInvalidInput(String body, String key) throws Exception {

        confirmedCard(myUserId, "배포 시간 단축", "A");

        create(body, key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter", Integer.class)).isZero();

    }

    static Stream<Arguments> invalidRequests() {

        String question = "{\"text\": \"협업 경험을 쓰세요\", \"charLimit\": 500}";
        return Stream.of(
                Arguments.of(body("\"  \"", "null", question), "k"),
                Arguments.of(body("\"" + "가".repeat(101) + "\"", "null", question), "k"),
                Arguments.of(body("\"백엔드\"", "\"" + "가".repeat(1_001) + "\"", question), "k"),
                Arguments.of(body("\"백엔드\"", "null", ""), "k"),
                Arguments.of(body("\"백엔드\"", "null", String.join(",", java.util.Collections.nCopies(11, question))), "k"),
                Arguments.of(body("\"백엔드\"", "null", "{\"text\": \" \", \"charLimit\": 500}"), "k"),
                Arguments.of(body("\"백엔드\"", "null", "{\"text\": \"" + "가".repeat(501) + "\", \"charLimit\": 500}"), "k"),
                Arguments.of(body("\"백엔드\"", "null", "{\"text\": \"문항\", \"charLimit\": 99}"), "k"),
                Arguments.of(body("\"백엔드\"", "null", "{\"text\": \"문항\", \"charLimit\": 5001}"), "k"),
                Arguments.of(body("\"백엔드\"", "null", "{\"text\": \"문\\u0000항\", \"charLimit\": 500}"), "k"),
                Arguments.of(BODY, "k".repeat(256)));

    }

    @Test
    @DisplayName("살아 있는 내 확정 카드가 없으면(작성 중 카드·지운 확정 카드·남의 확정 카드만 있으면) 422 NO_CONFIRMED_CARDS 이고, 만들지도 호출 제한을 세지도 않는다")
    void rejectsWhenNoConfirmedCards() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long deleted = confirmedCard(myUserId, "지운 카드", "A");
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);
        confirmedCard(othersUserId, "남의 카드", "A");

        create(BODY, "key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("NO_CONFIRMED_CARDS"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter", Integer.class)).isZero();
        verify(rateLimiter, never()).acquire(RateLimitedAction.COVER_LETTER, myUserId, 1);

    }

    @Test
    @DisplayName("같은 요청 키로 다시 오면 보낸 내용이 달라도 처음 만든 자소서를 돌려주고 호출 제한을 다시 세지 않는다")
    void sameKeyReturnsFirstLetterWithoutCountingAgain() throws Exception {

        confirmedCard(myUserId, "배포 시간 단축", "A");
        String firstId = idOf(create(BODY, "key-1"));

        create("{\"jobRole\": \"프론트엔드\", \"questions\": [{\"text\": \"다른 문항\"}]}", "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId))
                .andExpect(jsonPath("$.data.jobRole").value("백엔드 개발"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter", Integer.class)).isEqualTo(1);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.COVER_LETTER, myUserId, 1);

    }

    @Test
    @DisplayName("남의 자소서와 UUID 가 아닌 id 는 404 이고, 남의 자소서를 본 시도는 거절로 남긴다")
    void othersLetterIsNotFoundAndAudited() throws Exception {

        CoverLetter others = savedLetter(othersUserId, "협업 경험을 쓰세요");

        getLetter(others.getPublicId().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        getLetter("not-a-uuid").andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'VIEW_COVER_LETTER' AND outcome = 'DENIED'",
                Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("고른 카드·견준 카드·과정은 카드 공개 id 와 제목으로 내려가고, 재료는 중심 카드의 문장 칸과 전에 받은 답으로 정한다")
    void showsAssignedCardsByPublicIdWithMaterialsAndSteps() throws Exception {

        Long primary = confirmedCard(myUserId, "배포 시간 단축", "S", "A");
        Long supporting = confirmedCard(myUserId, "동아리 회계", "R");
        answers.save(CardMaterialAnswer.of(primary, MaterialItem.LEARNING, "배운 점"));
        CoverLetter letter = savedLetter(myUserId, "협업 경험을 쓰세요");
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.assign(QuestionTheme.COLLABORATION, primary, supporting, AssignedBy.SUPERVISOR, "함께 고친 경험",
                List.of(new ConsideredCard(supporting, "협업이 덜 드러남")));
        questions.save(question);
        assigned(letter, List.of(new AssignStep(1, "SUB_AGENT", "PICKED", 1, primary, "함께 고친 경험")));

        getLetter(letter.getPublicId().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].theme").value("COLLABORATION"))
                .andExpect(jsonPath("$.data.questions[0].primaryCard.id").value(publicIdOf(primary)))
                .andExpect(jsonPath("$.data.questions[0].primaryCard.title").value("배포 시간 단축"))
                .andExpect(jsonPath("$.data.questions[0].primaryCard.kind").value("QUALITATIVE"))
                .andExpect(jsonPath("$.data.questions[0].supportingCard.id").value(publicIdOf(supporting)))
                .andExpect(jsonPath("$.data.questions[0].assignedBy").value("SUPERVISOR"))
                .andExpect(jsonPath("$.data.questions[0].pickReason").value("함께 고친 경험"))
                .andExpect(jsonPath("$.data.questions[0].considered[0].cardId").value(publicIdOf(supporting)))
                .andExpect(jsonPath("$.data.questions[0].considered[0].title").value("동아리 회계"))
                .andExpect(jsonPath("$.data.questions[0].considered[0].note").value("협업이 덜 드러남"))
                .andExpect(jsonPath("$.data.questions[0].materials", contains("SITUATION", "ACTION", "LEARNING")))
                .andExpect(jsonPath("$.data.questions[0].primaryCardDeleted").value(false))
                .andExpect(jsonPath("$.data.steps.assign[0].questionId").value(question.getPublicId().toString()))
                .andExpect(jsonPath("$.data.steps.assign[0].cardId").value(publicIdOf(primary)))
                .andExpect(jsonPath("$.data.steps.assign[0].cardTitle").value("배포 시간 단축"));

    }

    @Test
    @DisplayName("지운 카드는 고른 카드·견준 카드·재료·과정에서 없는 카드로 보이고, 중심 카드를 지웠다고 알린다")
    void deletedCardShowsAsEmpty() throws Exception {

        Long primary = confirmedCard(myUserId, "배포 시간 단축", "A");
        CoverLetter letter = savedLetter(myUserId, "협업 경험을 쓰세요");
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.assign(QuestionTheme.COLLABORATION, primary, null, AssignedBy.SUB_AGENT, "이유",
                List.of(new ConsideredCard(primary, "평")));
        questions.save(question);
        assigned(letter, List.of(new AssignStep(1, "SUB_AGENT", "PICKED", 1, primary, "이유")));
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", primary);

        getLetter(letter.getPublicId().toString())
                .andExpect(jsonPath("$.data.questions[0].primaryCard").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].primaryCardDeleted").value(true))
                .andExpect(jsonPath("$.data.questions[0].considered").isEmpty())
                .andExpect(jsonPath("$.data.questions[0].materials").isEmpty())
                .andExpect(jsonPath("$.data.steps.assign[0].cardId").value(nullValue()))
                .andExpect(jsonPath("$.data.steps.assign[0].cardTitle").value(nullValue()));

    }

    @Test
    @DisplayName("실패한 자소서는 AI 가 잠시 못 한 실패만 다시 시도할 수 있다고 알린다")
    void onlyTemporaryFailureIsRetryable() throws Exception {

        CoverLetter temporary = failed(savedLetter(myUserId, "문항"), CoverLetterError.AI_UNAVAILABLE);
        CoverLetter broken = failed(savedLetter(myUserId, "문항"), CoverLetterError.INTERNAL_ERROR);

        getLetter(temporary.getPublicId().toString())
                .andExpect(jsonPath("$.data.stage").value("FAILED"))
                .andExpect(jsonPath("$.data.failedStep").value("ASSIGN"))
                .andExpect(jsonPath("$.data.errorCode").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.retryable").value(true));
        getLetter(broken.getPublicId().toString())
                .andExpect(jsonPath("$.data.retryable").value(false));

    }

    @Test
    @DisplayName("카드 고르기가 끝난 뒤 내 확정 카드로 바꾸면 사용자가 고른 것으로 남고 AI 가 고른 이유는 지운다")
    void choosesMyConfirmedCardsAfterAssignment() throws Exception {

        Long picked = confirmedCard(myUserId, "배포 시간 단축", "A");
        Long chosen = confirmedCard(myUserId, "동아리 회계", "A");
        CoverLetter letter = assignedLetterPicking(picked);
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();

        chooseCards(letter, question.getPublicId().toString(), publicIdOf(chosen), publicIdOf(picked))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].primaryCard.id").value(publicIdOf(chosen)))
                .andExpect(jsonPath("$.data.questions[0].supportingCard.id").value(publicIdOf(picked)))
                .andExpect(jsonPath("$.data.questions[0].assignedBy").value("USER"))
                .andExpect(jsonPath("$.data.questions[0].pickReason").value(nullValue()));

    }

    @Test
    @DisplayName("카드 고르기가 끝나기 전에는 409, 없는 문항은 404 로 카드를 바꾸지 않는다")
    void rejectsCardChoiceOutsideAssignedStageOrQuestion() throws Exception {

        Long card = confirmedCard(myUserId, "배포 시간 단축", "A");
        CoverLetter waiting = savedLetter(myUserId, "협업 경험을 쓰세요");
        String waitingQuestion = questions.findByCoverLetterIdOrderBySeq(waiting.getId()).getFirst().getPublicId()
                .toString();

        chooseCards(waiting, waitingQuestion, publicIdOf(card), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        chooseCards(assignedLetterPicking(card), UUID.randomUUID().toString(), publicIdOf(card), null)
                .andExpect(status().isNotFound());

    }

    @Test
    @DisplayName("남의 카드·작성 중 카드·지운 카드·중심과 같은 보조 카드는 400 으로 고를 수 없다")
    void rejectsCardsThatAreNotMyConfirmedOnes() throws Exception {

        Long mine = confirmedCard(myUserId, "배포 시간 단축", "A");
        Long others = confirmedCard(othersUserId, "남의 카드", "A");
        Long draft = fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long deleted = confirmedCard(myUserId, "지운 카드", "A");
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);
        CoverLetter letter = assignedLetterPicking(mine);
        String question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPublicId().toString();

        for (String[] cards : List.of(new String[]{publicIdOf(others), null}, new String[]{publicIdOf(draft), null},
                new String[]{publicIdOf(deleted), null}, new String[]{publicIdOf(mine), publicIdOf(mine)},
                new String[]{"not-a-uuid", null})) {
            chooseCards(letter, question, cards[0], cards[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getAssignedBy())
                .isEqualTo(AssignedBy.SUB_AGENT);

    }

    @Test
    @DisplayName("질문 만들기는 카드 고르기가 끝난 내 자소서만 줄 세우고 호출 제한을 세지 않으며, 그 뒤로는 카드를 바꿀 수 없다")
    void startsAskingOnlyAfterAssignment() throws Exception {

        Long card = confirmedCard(myUserId, "배포 시간 단축", "A");
        CoverLetter letter = assignedLetterPicking(card);
        CoverLetter waiting = savedLetter(myUserId, "문항");
        CoverLetter others = assigned(savedLetter(othersUserId, "문항"), List.of());

        startAsking(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ASKING"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1500))
                .andExpect(jsonPath("$.data.questions[0].primaryCard.id").value(publicIdOf(card)))
                .andExpect(jsonPath("$.data.questions[0].asks").isEmpty());
        startAsking(letter)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        startAsking(waiting).andExpect(status().isConflict());
        startAsking(others).andExpect(status().isNotFound());
        chooseCards(letter, questionIdOf(letter), publicIdOf(card), null).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM cover_letter WHERE stage = 'ASKING' AND running_since IS NULL",
                Integer.class)).isEqualTo(1);
        verifyNoInteractions(rateLimiter);

    }

    @Test
    @DisplayName("질문마다 받은 빈칸 틀을 저장해 두었다가 조회할 때 함께 내리고, 틀이 없는 질문은 빈 목록이다")
    void returnsTemplatesOfEachAsk() throws Exception {

        CoverLetter letter = askedLetter();

        getLetter(letter.getPublicId().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].asks[0].templates[0]").value("배포가 ___ 빨라졌다"))
                .andExpect(jsonPath("$.data.questions[0].asks[1].templates").isEmpty());

    }

    @Test
    @DisplayName("답은 문항의 답 전체를 바꾸고, 넘긴 답은 글 없이 남기며, 지난 답은 고치지 않고 다시 보낼 때만 미리 채운 답으로 남는다")
    void savesAnswersReplacingTheWholeQuestion() throws Exception {

        CoverLetter letter = askedLetter();
        String question = questionIdOf(letter);

        saveAnswers(letter, question, """
                {"answers": [{"item": "RESULT", "text": "배포가 3분 빨라졌다", "skipped": false},
                             {"item": "TRIGGER", "text": null, "skipped": true},
                             {"item": "LEARNING", "text": "작게 나눠 배포하게 됐다"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ASKED"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].asks[0].item").value("RESULT"))
                .andExpect(jsonPath("$.data.questions[0].asks[0].question").value("배포가 얼마나 빨라졌나요?"))
                .andExpect(jsonPath("$.data.questions[0].asks[0].required").value(true))
                .andExpect(jsonPath("$.data.questions[0].asks[0].answer.text").value("배포가 3분 빨라졌다"))
                .andExpect(jsonPath("$.data.questions[0].asks[0].answer.reused").value(false))
                .andExpect(jsonPath("$.data.questions[0].asks[1].answer").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].asks[1].skipped").value(true))
                .andExpect(jsonPath("$.data.questions[0].asks[2].question").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].asks[2].answer.reused").value(true));

        saveAnswers(letter, question, """
                {"answers": [{"item": "LEARNING", "text": "작게 나눠 자주 배포하게 됐다"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].asks[0].answer").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].asks[0].skipped").value(false))
                .andExpect(jsonPath("$.data.questions[0].asks[1].skipped").value(false))
                .andExpect(jsonPath("$.data.questions[0].asks[2].answer.text").value("작게 나눠 자주 배포하게 됐다"))
                .andExpect(jsonPath("$.data.questions[0].asks[2].answer.reused").value(false));

    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    @DisplayName("질문에 없거나 모르는 항목, 같은 항목 두 번, 넘기지 않았는데 비었거나 1,000자를 넘거나 U+0000 이 있는 답은 400 이고 답을 바꾸지 않는다")
    void rejectsInvalidAnswers(String body) throws Exception {

        CoverLetter letter = askedLetter();

        saveAnswers(letter, questionIdOf(letter), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getAnswers())
                .containsExactly(new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 배포하게 됐다", false, true));

    }

    static Stream<String> invalidAnswers() {

        return Stream.of(
                "{\"answers\": null}",
                "{\"answers\": [null]}",
                "{\"answers\": [{\"item\": \"JOB_LINK\", \"text\": \"질문에 없는 항목\"}]}",
                "{\"answers\": [{\"item\": \"HOBBY\", \"text\": \"모르는 항목\"}]}",
                "{\"answers\": [{\"item\": null, \"text\": \"항목 없음\"}]}",
                "{\"answers\": [{\"item\": \"RESULT\", \"text\": \"한 번\"}, {\"item\": \"RESULT\", \"text\": \"두 번\"}]}",
                "{\"answers\": [{\"item\": \"RESULT\", \"text\": \" \"}]}",
                "{\"answers\": [{\"item\": \"RESULT\", \"text\": null}]}",
                "{\"answers\": [{\"item\": \"RESULT\", \"text\": \"" + "가".repeat(1_001) + "\"}]}",
                "{\"answers\": [{\"item\": \"RESULT\", \"text\": \"a\\u0000b\"}]}");

    }

    @Test
    @DisplayName("답 1,000자까지는 저장한다")
    void savesAnswerUpToTheLimit() throws Exception {

        CoverLetter letter = askedLetter();

        saveAnswers(letter, questionIdOf(letter), "{\"answers\": [{\"item\": \"RESULT\", \"text\": \""
                + "가".repeat(1_000) + "\"}]}")
                .andExpect(status().isOk());

    }

    @Test
    @DisplayName("질문을 받기 전에는 409, 없는 문항은 404 로 답을 저장하지 않는다")
    void rejectsAnswersOutsideAskedStageOrQuestion() throws Exception {

        Long card = confirmedCard(myUserId, "배포 시간 단축", "A");
        CoverLetter assigned = assignedLetterPicking(card);

        saveAnswers(assigned, questionIdOf(assigned), "{\"answers\": []}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        saveAnswers(askedLetter(), UUID.randomUUID().toString(), "{\"answers\": []}")
                .andExpect(status().isNotFound());

    }

    @Test
    @DisplayName("초안 쓰기는 질문을 받은 내 자소서만 줄 세우고, 답하지 않은 질문은 넘긴 것으로 두며, 경험 답을 중심 카드에 기억하되 넘긴 항목의 옛 답과 지운 카드는 건드리지 않는다")
    void startsDraftingAndRemembersAnswersOnPrimaryCard() throws Exception {

        Long card = confirmedCard(myUserId, "배포 시간 단축", "A");
        Long deleted = confirmedCard(myUserId, "지운 카드", "A");
        answers.save(CardMaterialAnswer.of(card, MaterialItem.TRIGGER, "예전 계기"));
        answers.save(CardMaterialAnswer.of(card, MaterialItem.LEARNING, "작게 나눠 배포하게 됐다"));
        CoverLetter letter = askedLetterWith(
                new Asked(card, List.of(new MaterialAsk(MaterialItem.RESULT, "결과는요?", true, List.of()),
                        new MaterialAsk(MaterialItem.TRIGGER, "계기는요?", false, List.of()),
                        new MaterialAsk(MaterialItem.LEARNING, null, false, List.of())),
                        List.of(new MaterialAnswer(MaterialItem.RESULT, "첫 문항의 결과", false, false),
                                new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 자주 배포하게 됐다", false, false))),
                new Asked(card, List.of(new MaterialAsk(MaterialItem.RESULT, "결과는요?", true, List.of()),
                        new MaterialAsk(MaterialItem.JOB_LINK, "직무와 어떻게 이어지나요?", false, List.of())),
                        List.of(new MaterialAnswer(MaterialItem.RESULT, "둘째 문항의 결과", false, false),
                                new MaterialAnswer(MaterialItem.JOB_LINK, "결제 서비스와 이어진다", false, false))),
                new Asked(deleted, List.of(new MaterialAsk(MaterialItem.ACTION, "무엇을 했나요?", true, List.of())),
                        List.of(new MaterialAnswer(MaterialItem.ACTION, "지운 카드의 행동", false, false))));
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);

        startDrafting(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("DRAFTING"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1500))
                .andExpect(jsonPath("$.data.questions[0].asks[1].answer").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].asks[1].skipped").value(true));
        startDrafting(letter)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(rememberedOf(card)).containsExactlyInAnyOrderEntriesOf(Map.of(
                MaterialItem.RESULT, "둘째 문항의 결과",
                MaterialItem.LEARNING, "작게 나눠 자주 배포하게 됐다",
                MaterialItem.TRIGGER, "예전 계기"));
        assertThat(rememberedOf(deleted)).isEmpty();
        verifyNoInteractions(rateLimiter);

    }

    @Test
    @DisplayName("질문을 받기 전에는 초안 쓰기를 시작하지 않는다")
    void rejectsDraftingBeforeAsked() throws Exception {

        startDrafting(assignedLetterPicking(confirmedCard(myUserId, "배포 시간 단축", "A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

    }

    @Test
    @DisplayName("초안을 고치면 AI 원본·문장·표시는 두고 고친 글로 글자 수·넘은 수·남은 빈칸을 다시 세며, 원본과 같아지면 고치지 않은 것으로 본다")
    void editsDraftAndRecountsFromEditedText() throws Exception {

        String aiText = "가".repeat(90) + " " + LEARNING_BLANK;
        CoverLetter letter = draftedLetter(aiText);
        String question = questionIdOf(letter);
        String edited = "가".repeat(100) + "😀" + "나".repeat(9);

        editDraft(letter, question, "{\"text\": \"" + edited + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].draft.text").value(edited))
                .andExpect(jsonPath("$.data.questions[0].draft.aiText").value(aiText))
                .andExpect(jsonPath("$.data.questions[0].draft.edited").value(true))
                .andExpect(jsonPath("$.data.questions[0].draft.charCount").value(110))
                .andExpect(jsonPath("$.data.questions[0].draft.overBy").value(10))
                .andExpect(jsonPath("$.data.questions[0].draft.blanks").isEmpty())
                .andExpect(jsonPath("$.data.questions[0].draft.sentences[0].text").value("가".repeat(90)))
                .andExpect(jsonPath("$.data.questions[0].draft.marks[0].code").value("UNSOURCED_NUMBER"))
                .andExpect(jsonPath("$.data.questions[0].draft.source").value("LLM"))
                .andExpect(jsonPath("$.data.steps.draft[0].kind").value("WROTE"))
                .andExpect(jsonPath("$.data.steps.draft[0].questionId").value(question));

        editDraft(letter, question, "{\"text\": \"" + aiText + "\"}")
                .andExpect(jsonPath("$.data.questions[0].draft.edited").value(false))
                .andExpect(jsonPath("$.data.questions[0].draft.charCount").value(91))
                .andExpect(jsonPath("$.data.questions[0].draft.overBy").value(0))
                .andExpect(jsonPath("$.data.questions[0].draft.blanks", contains("LEARNING")));

    }

    @ParameterizedTest
    @MethodSource("invalidEdits")
    @DisplayName("고친 글이 없거나 10,000자를 넘거나 U+0000 이 있으면 400 이고 글을 바꾸지 않는다")
    void rejectsInvalidEdits(String body) throws Exception {

        CoverLetter letter = draftedLetter("초안");

        editDraft(letter, questionIdOf(letter), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getDraftText()).isEqualTo("초안");

    }

    static Stream<String> invalidEdits() {

        return Stream.of("{\"text\": null}", "{\"text\": \"" + "가".repeat(10_001) + "\"}", "{\"text\": \"a\\u0000b\"}");

    }

    @Test
    @DisplayName("고친 글은 10,000자까지 저장하고, 초안을 받기 전에는 409, 없는 문항은 404 다")
    void editsUpToTheLimitOnlyOnDraftedQuestion() throws Exception {

        CoverLetter drafted = draftedLetter("초안");
        CoverLetter asked = askedLetter();

        editDraft(drafted, questionIdOf(drafted), "{\"text\": \"" + "가".repeat(10_000) + "\"}")
                .andExpect(status().isOk());
        editDraft(asked, questionIdOf(asked), "{\"text\": \"고친 글\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        editDraft(drafted, UUID.randomUUID().toString(), "{\"text\": \"고친 글\"}")
                .andExpect(status().isNotFound());

    }

    @Test
    @DisplayName("목록은 내 자소서만 최근에 만든 것부터 문항 수·첫 문항과 함께 주고, 없으면 빈 목록이다")
    void listsMyLettersNewestFirst() throws Exception {

        CoverLetter older = savedLetter(myUserId, "협업 경험을 쓰세요", "실패 경험을 쓰세요");
        CoverLetter newer = savedLetter(myUserId, "지원 동기를 쓰세요");
        savedLetter(othersUserId, "남의 문항");
        jdbc.update("UPDATE cover_letter SET created_at = created_at - interval '1 day' WHERE id = ?", older.getId());

        mvc.perform(get("/api/cover-letters").with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(newer.getPublicId().toString()))
                .andExpect(jsonPath("$.data[0].questionCount").value(1))
                .andExpect(jsonPath("$.data[0].firstQuestion").value("지원 동기를 쓰세요"))
                .andExpect(jsonPath("$.data[1].id").value(older.getPublicId().toString()))
                .andExpect(jsonPath("$.data[1].stage").value("ASSIGNING"))
                .andExpect(jsonPath("$.data[1].questionCount").value(2))
                .andExpect(jsonPath("$.data[1].firstQuestion").value("협업 경험을 쓰세요"));
        mvc.perform(get("/api/cover-letters").with(loggedInAs(fixtures.insertUser(3L, "newcomer"))))
                .andExpect(jsonPath("$.data").isEmpty());

    }

    @Test
    @DisplayName("지우기는 도는 단계여도 내 자소서와 문항을 지우고 카드에 기억한 답은 남기며, 남의 자소서는 404 와 거절 기록이다")
    void deletesMyLetterAtAnyStage() throws Exception {

        Long card = confirmedCard(myUserId, "배포 시간 단축", "A");
        answers.save(CardMaterialAnswer.of(card, MaterialItem.RESULT, "기억한 답"));
        CoverLetter running = savedLetter(myUserId, "문항");
        running.claim();
        letters.save(running);
        CoverLetter others = savedLetter(othersUserId, "문항");

        deleteLetter(running)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
        deleteLetter(others).andExpect(status().isNotFound());

        getLetter(running.getPublicId().toString()).andExpect(status().isNotFound());
        assertThat(questions.findByCoverLetterIdOrderBySeq(running.getId())).isEmpty();
        assertThat(rememberedOf(card)).containsEntry(MaterialItem.RESULT, "기억한 답");
        assertThat(letters.findById(others.getId())).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'DELETE_COVER_LETTER' AND outcome = 'DENIED'",
                Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("다시 시도는 실패한 자소서의 실패한 단계만 다시 줄 세우고 호출 제한을 센다")
    void retryRequeuesOnlyFailedLetter() throws Exception {

        CoverLetter failed = failed(savedLetter(myUserId, "문항"), CoverLetterError.AI_UNAVAILABLE);
        CoverLetter waiting = savedLetter(myUserId, "문항");

        retry(failed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ASSIGNING"))
                .andExpect(jsonPath("$.data.failedStep").value(nullValue()))
                .andExpect(jsonPath("$.data.errorCode").value(nullValue()))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1500));
        retry(waiting)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        verify(rateLimiter, times(1)).acquire(RateLimitedAction.COVER_LETTER, myUserId, 1);
        verify(rateLimiter, never()).acquire(RateLimitedAction.COVER_LETTER, othersUserId, 1);

    }

    private static String body(String jobRole, String memo, String questions) {
        return "{\"jobRole\": " + jobRole + ", \"memo\": " + memo + ", \"questions\": [" + questions + "]}";
    }

    private Long confirmedCard(Long userId, String title, String... slots) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        for (String slot : slots) {
            fixtures.insertStatement(card, 1, slot, slot + " 문장", "USER_STATED", null);
        }
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET title = ? WHERE id = ?", title, card);
        return card;

    }

    private String publicIdOf(Long cardId) {
        return jdbc.queryForObject("SELECT public_id FROM card WHERE id = ?", UUID.class, cardId).toString();
    }

    private CoverLetter savedLetter(Long userId, String... texts) {

        CoverLetter letter = letters.save(CoverLetter.start(userId, "백엔드 개발", null, null));
        for (int i = 0; i < texts.length; i++) {
            questions.save(CoverLetterQuestion.of(letter.getId(), i + 1, texts[i], 700));
        }
        return letter;

    }

    private CoverLetter assigned(CoverLetter letter, List<AssignStep> steps) {

        letter.claim();
        letter.assigned(steps);
        return letters.save(letter);

    }

    private CoverLetter assignedLetterPicking(Long cardId) {

        CoverLetter letter = savedLetter(myUserId, "협업 경험을 쓰세요");
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.assign(QuestionTheme.COLLABORATION, cardId, null, AssignedBy.SUB_AGENT, "이유", List.of());
        questions.save(question);
        return assigned(letter, List.of());

    }

    private CoverLetter askedLetter() {

        CoverLetter letter = assignedLetterPicking(confirmedCard(myUserId, "배포 시간 단축", "A"));
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.asked(List.of(new MaterialAsk(MaterialItem.RESULT, "배포가 얼마나 빨라졌나요?", true,
                                List.of("배포가 ___ 빨라졌다")),
                        new MaterialAsk(MaterialItem.TRIGGER, "왜 고치기로 했나요?", false, List.of()),
                        new MaterialAsk(MaterialItem.LEARNING, null, false, List.of())),
                List.of(new MaterialAnswer(MaterialItem.LEARNING, "작게 나눠 배포하게 됐다", false, true)));
        questions.save(question);
        letter.startAsking();
        letter.claim();
        letter.asked();
        return letters.save(letter);

    }

    private CoverLetter askedLetterWith(Asked... picks) {

        CoverLetter letter = letters.save(CoverLetter.start(myUserId, "백엔드 개발", null, null));
        for (int i = 0; i < picks.length; i++) {
            CoverLetterQuestion question = CoverLetterQuestion.of(letter.getId(), i + 1, "문항 " + (i + 1), 700);
            question.assign(QuestionTheme.COLLABORATION, picks[i].card(), null, AssignedBy.SUB_AGENT, "이유", List.of());
            question.asked(picks[i].asks(), picks[i].answers());
            questions.save(question);
        }
        letter.claim();
        letter.assigned(List.of());
        letter.startAsking();
        letter.claim();
        letter.asked();
        return letters.save(letter);

    }

    private record Asked(Long card, List<MaterialAsk> asks, List<MaterialAnswer> answers) {
    }

    private CoverLetter draftedLetter(String aiText) {

        CoverLetter letter = letters.save(CoverLetter.start(myUserId, "백엔드 개발", null, null));
        CoverLetterQuestion question = CoverLetterQuestion.of(letter.getId(), 1, "협업 경험을 쓰세요", 100);
        question.drafted(aiText, List.of(new DraftSentence(1, "가".repeat(90))), 91, 0,
                List.of(new DraftMark(1, "UNSOURCED_NUMBER", "90")), List.of(MaterialItem.LEARNING), DraftSource.LLM);
        questions.save(question);
        letter.claim();
        letter.assigned(List.of());
        letter.startAsking();
        letter.claim();
        letter.asked();
        letter.startDrafting();
        letter.claim();
        letter.drafted(List.of(new DraftStep(1, "SUB_AGENT", "WROTE", 1, null, "91", null, null, null)));
        return letters.save(letter);

    }

    private Map<MaterialItem, String> rememberedOf(Long cardId) {

        return answers.findByCardIdIn(List.of(cardId)).stream()
                .collect(Collectors.toMap(CardMaterialAnswer::getItem, CardMaterialAnswer::getText));

    }

    private String questionIdOf(CoverLetter letter) {
        return questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPublicId().toString();
    }

    private CoverLetter failed(CoverLetter letter, CoverLetterError error) {

        letter.claim();
        letter.fail(error);
        return letters.save(letter);

    }

    private ResultActions create(String body, String key) throws Exception {

        return mvc.perform(post("/api/cover-letters").with(loggedInAs(myUserId)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions getLetter(String id) throws Exception {
        return mvc.perform(get("/api/cover-letters/{id}", id).with(loggedInAs(myUserId)));
    }

    private ResultActions chooseCards(CoverLetter letter, String questionId, String primary, String supporting)
            throws Exception {

        String body = "{\"primaryCardId\": " + quoted(primary) + ", \"supportingCardId\": " + quoted(supporting) + "}";
        return mvc.perform(put("/api/cover-letters/{id}/questions/{qid}/cards", letter.getPublicId(), questionId)
                .with(loggedInAs(myUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions startAsking(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/asks", letter.getPublicId())
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions saveAnswers(CoverLetter letter, String questionId, String body) throws Exception {

        return mvc.perform(put("/api/cover-letters/{id}/questions/{qid}/answers", letter.getPublicId(), questionId)
                .with(loggedInAs(myUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions startDrafting(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/drafts", letter.getPublicId())
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions editDraft(CoverLetter letter, String questionId, String body) throws Exception {

        return mvc.perform(patch("/api/cover-letters/{id}/questions/{qid}", letter.getPublicId(), questionId)
                .with(loggedInAs(myUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions deleteLetter(CoverLetter letter) throws Exception {

        return mvc.perform(delete("/api/cover-letters/{id}", letter.getPublicId())
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions retry(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/retry", letter.getPublicId())
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private String idOf(ResultActions response) throws Exception {

        return com.jayway.jsonpath.JsonPath.read(response.andReturn().getResponse().getContentAsString(), "$.data.id");

    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
