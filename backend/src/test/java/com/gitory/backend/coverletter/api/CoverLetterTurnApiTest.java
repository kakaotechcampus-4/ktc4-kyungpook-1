package com.gitory.backend.coverletter.api;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterFill;
import com.gitory.backend.agent.port.CoverLetterFillRequest;
import com.gitory.backend.agent.port.CoverLetterTurnPort;
import com.gitory.backend.agent.port.CoverLetterTurnReply;
import com.gitory.backend.agent.port.CoverLetterTurnRequest;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.AssignedBy;
import com.gitory.backend.coverletter.domain.CoverLetter;
import com.gitory.backend.coverletter.domain.CoverLetterQuestion;
import com.gitory.backend.coverletter.domain.CoverLetterTurn;
import com.gitory.backend.coverletter.domain.DraftMark;
import com.gitory.backend.coverletter.domain.DraftSentence;
import com.gitory.backend.coverletter.domain.DraftSource;
import com.gitory.backend.coverletter.domain.MaterialAnswer;
import com.gitory.backend.coverletter.domain.MaterialAsk;
import com.gitory.backend.coverletter.domain.MaterialItem;
import com.gitory.backend.coverletter.domain.PolishedDraft;
import com.gitory.backend.coverletter.domain.QuestionPolish;
import com.gitory.backend.coverletter.domain.QuestionTheme;
import com.gitory.backend.coverletter.domain.TurnStatus;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.coverletter.infra.CoverLetterTurnRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class CoverLetterTurnApiTest {

    private static final String BLANK = "[행동: 직접 한 일을 적어 주세요]";
    private static final String AI_TEXT = "배포를 함께 고쳤다. " + BLANK;
    private static final String NEW_ANSWER = "AI 담당 팀원과 로그를 같이 보며 원인을 좁혔다";
    private static final CoverLetterTurnReply ASKING = new CoverLetterTurnReply(
            new CoverLetterTurnReply.Message("그때 직접 한 일을 한 줄만 알려 주세요.", 1, "ACTION"), null, "ASKING");
    private static final CoverLetterTurnReply FILLED = new CoverLetterTurnReply(
            new CoverLetterTurnReply.Message("로그를 같이 봤군요. 이 답으로 채울게요.", 1, "ACTION"),
            new CoverLetterTurnReply.Answer("ACTION", NEW_ANSWER, true), "FILLED");
    private static final CoverLetterDrafts.Draft REWRITTEN = new CoverLetterDrafts.Draft(1,
            "배포를 함께 고쳤다. AI 담당 팀원과 로그를 같이 보며 원인을 좁혔다.",
            List.of(new CoverLetterDrafts.Sentence(1, "배포를 함께 고쳤다."),
                    new CoverLetterDrafts.Sentence(2, "AI 담당 팀원과 로그를 같이 보며 원인을 좁혔다.")),
            36, 0, List.of(new CoverLetterDrafts.Mark(2, "UNSOURCED_TERM", "로그")), List.of(), "LLM");

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
    CoverLetterTurnRepository turns;

    @MockitoBean
    CoverLetterTurnPort turnPort;

    @MockitoBean
    UserRateLimiter rateLimiter;

    private final ObjectMapper mapper = JsonMapper.builder().build();

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
    @DisplayName("문항 이야기를 시작하면 AI 의 첫 말을 대화에 더하고 그 문항과 함께 돌려주며, 호출 제한을 한 번 센다")
    void openStoresTheFirstAiTurn() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(ASKING);

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(1))
                .andExpect(jsonPath("$.data.turns[0].seq").value(1))
                .andExpect(jsonPath("$.data.turns[0].role").value("AI"))
                .andExpect(jsonPath("$.data.turns[0].questionId").value(questionIdOf(letter, 1).toString()))
                .andExpect(jsonPath("$.data.turns[0].text").value("그때 직접 한 일을 한 줄만 알려 주세요."))
                .andExpect(jsonPath("$.data.turns[0].item").value("ACTION"))
                .andExpect(jsonPath("$.data.turns[0].status").value("ASKING"))
                .andExpect(jsonPath("$.data.turns[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.question.id").value(questionIdOf(letter, 1).toString()))
                .andExpect(jsonPath("$.data.question.draft.text").value(AI_TEXT))
                .andExpect(jsonPath("$.data.rewriteFailed").value(false));

        CoverLetterTurnRequest sent = sentTurn();
        assertThat(sent.trigger()).isEqualTo("OPEN");
        assertThat(sent.thread()).isEmpty();
        assertThat(sent.question().currentText()).isEqualTo(AI_TEXT);
        assertThat(sent.question().blanks()).containsExactly(BLANK);
        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER_TURN, myUserId, 1);
        verify(turnPort, never()).fill(any());

    }

    @Test
    @DisplayName("답을 보내면 사용자의 말과 AI 의 다음 말을 차례로 더하고, AI 에는 앞 대화 뒤에 방금 말을 붙여 보내며, 채울 답이 아니면 문항 답을 바꾸지 않는다")
    void replyStoresUserAndAiTurns() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        turns.save(CoverLetterTurn.ai(letter.getId(), 1, 1, "그때 직접 한 일을 알려 주세요.", MaterialItem.ACTION,
                TurnStatus.ASKING));
        when(turnPort.next(any())).thenReturn(ASKING);

        reply(letter, "로그를 봤어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(3))
                .andExpect(jsonPath("$.data.turns[1].seq").value(2))
                .andExpect(jsonPath("$.data.turns[1].role").value("USER"))
                .andExpect(jsonPath("$.data.turns[1].text").value("로그를 봤어요"))
                .andExpect(jsonPath("$.data.turns[1].item").value(nullValue()))
                .andExpect(jsonPath("$.data.turns[1].status").value(nullValue()))
                .andExpect(jsonPath("$.data.turns[2].seq").value(3))
                .andExpect(jsonPath("$.data.turns[2].role").value("AI"));

        CoverLetterTurnRequest sent = sentTurn();
        assertThat(sent.trigger()).isEqualTo("USER_REPLY");
        assertThat(sent.thread()).containsExactly(
                new CoverLetterTurnRequest.Turn(1, "AI", 1, "그때 직접 한 일을 알려 주세요.", "ACTION", "ASKING"),
                new CoverLetterTurnRequest.Turn(2, "USER", 1, "로그를 봤어요", null, null));
        assertThat(firstQuestion(letter).getAnswers()).extracting(MaterialAnswer::text)
                .containsExactly("옛 행동 답", "배포가 빨라졌다");
        verify(turnPort, never()).fill(any());

    }

    @Test
    @DisplayName("넘기면 사용자의 말 없이 AI 의 다음 말만 더한다")
    void skipStoresOnlyTheAiTurn() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(new CoverLetterTurnReply(
                new CoverLetterTurnReply.Message("빈칸은 그대로 둘게요.", 1, null), null, "DONE"));

        skip(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(1))
                .andExpect(jsonPath("$.data.turns[0].role").value("AI"))
                .andExpect(jsonPath("$.data.turns[0].status").value("DONE"));

        assertThat(sentTurn().trigger()).isEqualTo("SKIP");

    }

    @Test
    @DisplayName("채울 수 있는 답이면 같은 항목의 앞 답 뒤에 덧붙여 저장하고 중심 카드에도 기억하며, 그 답과 채울 빈칸을 실어 다시 쓰기를 부른다")
    void filledAnswerIsAppendedToTheSameItemAndRemembered() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(FILLED);
        when(turnPort.fill(any())).thenReturn(new CoverLetterFill(REWRITTEN, false));

        reply(letter, "AI 담당 팀원과 로그를 같이 봤어요").andExpect(status().isOk());

        assertThat(firstQuestion(letter).getAnswers()).containsExactly(
                new MaterialAnswer(MaterialItem.ACTION, "옛 행동 답 " + NEW_ANSWER, false, false),
                new MaterialAnswer(MaterialItem.RESULT, "배포가 빨라졌다", false, false));
        assertThat(jdbc.queryForObject("SELECT text FROM card_material_answer WHERE card_id = ? AND item = 'ACTION'",
                String.class, firstQuestion(letter).getPrimaryCardId())).isEqualTo("옛 행동 답 " + NEW_ANSWER);
        ArgumentCaptor<CoverLetterFillRequest> fill = ArgumentCaptor.forClass(CoverLetterFillRequest.class);
        verify(turnPort).fill(fill.capture());
        assertThat(fill.getValue().filledItem()).isEqualTo("ACTION");
        assertThat(fill.getValue().blank()).isEqualTo(BLANK);
        assertThat(fill.getValue().target().currentText()).isEqualTo(AI_TEXT);
        assertThat(fill.getValue().target().answers()).extracting(answer -> answer.text()).contains("옛 행동 답 " + NEW_ANSWER);

    }

    @Test
    @DisplayName("같은 항목에 같은 답이 다시 오면 덧붙이지 않고, 덧붙인 답이 1,000자를 넘으면 1,000자에서 자른다")
    void sameAnswerIsNotRepeatedAndJoinedAnswerIsCut() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.fill(any())).thenReturn(new CoverLetterFill(REWRITTEN, false));
        when(turnPort.next(any())).thenReturn(new CoverLetterTurnReply(FILLED.message(),
                new CoverLetterTurnReply.Answer("ACTION", "옛 행동 답", true), "FILLED"));

        reply(letter, "옛 행동 답이에요").andExpect(status().isOk());
        assertThat(firstQuestion(letter).getAnswers().getFirst().text()).isEqualTo("옛 행동 답");

        when(turnPort.next(any())).thenReturn(new CoverLetterTurnReply(FILLED.message(),
                new CoverLetterTurnReply.Answer("ACTION", "가".repeat(1_000), true), "FILLED"));
        reply(letter, "아주 긴 답이에요").andExpect(status().isOk());
        assertThat(firstQuestion(letter).getAnswers().getFirst().text())
                .hasSize(1_000)
                .startsWith("옛 행동 답 가");

    }

    @Test
    @DisplayName("직무 연결·지원 이유 답은 문항 답에는 더하지만 카드에는 기억하지 않는다")
    void jobLinkAndReasonAreNotRemembered() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(new CoverLetterTurnReply(
                new CoverLetterTurnReply.Message("이 이유로 채울게요.", 1, "REASON"),
                new CoverLetterTurnReply.Answer("REASON", "결제 시스템을 다뤄 보고 싶다", true), "FILLED"));
        when(turnPort.fill(any())).thenReturn(new CoverLetterFill(REWRITTEN, false));

        reply(letter, "결제 시스템을 다뤄 보고 싶어요").andExpect(status().isOk());

        assertThat(firstQuestion(letter).getAnswers()).extracting(MaterialAnswer::item)
                .containsExactly(MaterialItem.ACTION, MaterialItem.RESULT, MaterialItem.REASON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_material_answer", Integer.class)).isZero();

    }

    @Test
    @DisplayName("다시 쓴 글로 지금 글·문장·표시·빈칸·글자 수를 바꾸고 고친 적 없음으로 두며, AI 원본은 그대로 두고 옛 글의 첨삭 결과는 비운다")
    void rewriteReplacesTheDraftAndKeepsAiText() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        withPolish(letter);
        when(turnPort.next(any())).thenReturn(FILLED);
        when(turnPort.fill(any())).thenReturn(new CoverLetterFill(REWRITTEN, false));

        reply(letter, "AI 담당 팀원과 로그를 같이 봤어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(false))
                .andExpect(jsonPath("$.data.question.draft.text").value(REWRITTEN.text()))
                .andExpect(jsonPath("$.data.question.draft.aiText").value(AI_TEXT))
                .andExpect(jsonPath("$.data.question.draft.edited").value(false))
                .andExpect(jsonPath("$.data.question.draft.charCount").value(36))
                .andExpect(jsonPath("$.data.question.draft.sentences.length()").value(2))
                .andExpect(jsonPath("$.data.question.draft.marks[0].code").value("UNSOURCED_TERM"))
                .andExpect(jsonPath("$.data.question.draft.blanks").isEmpty())
                .andExpect(jsonPath("$.data.question.polish").value(nullValue()))
                .andExpect(jsonPath("$.data.turns[1].status").value("FILLED"));

        CoverLetterQuestion saved = firstQuestion(letter);
        assertThat(saved.getAiText()).isEqualTo(AI_TEXT);
        assertThat(saved.getDraftText()).isEqualTo(REWRITTEN.text());
        assertThat(saved.getPolish()).isNull();

    }

    @Test
    @DisplayName("다시 쓰기가 실패하면 대화와 답은 남기고 글은 그대로 둔 채 rewriteFailed 를 true 로 돌려준다")
    void failedRewriteKeepsTurnsAndAnswerButNotTheDraft() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(FILLED);
        when(turnPort.fill(any())).thenThrow(new AgentUnavailableException("AI 자소서 대화 다시 쓰기 실패", true, false, null));

        reply(letter, "AI 담당 팀원과 로그를 같이 봤어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(true))
                .andExpect(jsonPath("$.data.turns.length()").value(2))
                .andExpect(jsonPath("$.data.question.draft.text").value(AI_TEXT));

        assertThat(firstQuestion(letter).getDraftText()).isEqualTo(AI_TEXT);
        assertThat(firstQuestion(letter).getAnswers().getFirst().text()).isEqualTo("옛 행동 답 " + NEW_ANSWER);

    }

    @Test
    @DisplayName("AI 가 다시 쓴 글을 되돌렸다고 하면 대화와 답은 남기고 글은 그대로 둔 채 rewriteFailed 를 true 로 돌려준다")
    void revertedRewriteKeepsTheDraft() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(FILLED);
        when(turnPort.fill(any())).thenReturn(new CoverLetterFill(new CoverLetterDrafts.Draft(1, AI_TEXT,
                List.of(new CoverLetterDrafts.Sentence(1, "배포를 함께 고쳤다."), new CoverLetterDrafts.Sentence(2, BLANK)),
                11, 0, List.of(), List.of("ACTION"), "LLM"), true));

        reply(letter, "AI 담당 팀원과 로그를 같이 봤어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(true))
                .andExpect(jsonPath("$.data.turns.length()").value(2))
                .andExpect(jsonPath("$.data.question.draft.marks[0].code").value("INFERRED"));

        assertThat(firstQuestion(letter).getAnswers().getFirst().text()).isEqualTo("옛 행동 답 " + NEW_ANSWER);

    }

    @Test
    @DisplayName("다시 쓰는 동안 사용자가 글을 고쳤으면 고친 글을 덮지 않고 rewriteFailed 를 true 로 돌려준다")
    void rewriteDoesNotOverwriteAnEditMadeMeanwhile() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(FILLED);
        when(turnPort.fill(any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE cover_letter_question SET draft_text = '사용자가 그사이 고친 글', edited = true WHERE id = ?",
                    firstQuestion(letter).getId());
            return new CoverLetterFill(REWRITTEN, false);
        });

        reply(letter, "AI 담당 팀원과 로그를 같이 봤어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(true))
                .andExpect(jsonPath("$.data.question.draft.text").value("사용자가 그사이 고친 글"));

    }

    @Test
    @DisplayName("대화 읽기는 자소서의 모든 말을 순번대로 돌려주고 말마다 그 문항의 공개 id 를 단다")
    void listsTurnsInSeqOrderWithQuestionIds() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        turns.save(CoverLetterTurn.ai(letter.getId(), 2, 2, "지원 동기를 알려 주세요.", MaterialItem.REASON,
                TurnStatus.ASKING));
        turns.save(CoverLetterTurn.ai(letter.getId(), 1, 1, "그때 직접 한 일을 알려 주세요.", MaterialItem.ACTION,
                TurnStatus.DONE));

        mvc.perform(get("/api/cover-letters/{id}/turns", letter.getPublicId()).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(2))
                .andExpect(jsonPath("$.data.turns[0].seq").value(1))
                .andExpect(jsonPath("$.data.turns[0].questionId").value(questionIdOf(letter, 1).toString()))
                .andExpect(jsonPath("$.data.turns[1].seq").value(2))
                .andExpect(jsonPath("$.data.turns[1].questionId").value(questionIdOf(letter, 2).toString()));

    }

    @Test
    @DisplayName("초안을 다 쓰기 전에는 이야기 시작·보내기·넘기기가 409 이고, AI 를 부르지도 호출 제한을 세지도 않는다")
    void rejectsBeforeDrafted() throws Exception {

        CoverLetter asked = askedLetter();

        open(asked).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        reply(asked, "로그를 봤어요").andExpect(status().isConflict());
        skip(asked).andExpect(status().isConflict());

        verifyNoInteractions(turnPort);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());
        assertThat(turns.count()).isZero();

    }

    @Test
    @DisplayName("보낸 말이 없거나 공백뿐이거나 500자를 넘거나 U+0000 이 있으면 400 이고, AI 를 부르지도 호출 제한을 세지도 않는다")
    void rejectsInvalidText() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenReturn(ASKING);

        for (String text : new String[]{null, "  ", "가".repeat(501), "로그\u0000"}) {
            reply(letter, text).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        reply(letter, "가".repeat(500)).andExpect(status().isOk());

        verify(turnPort).next(any());
        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER_TURN, myUserId, 1);

    }

    @Test
    @DisplayName("남의 자소서는 읽기·이야기 시작·보내기·넘기기 모두 404 이고, AI 를 부르지 않고 거절로 남긴다")
    void othersLetterIsNotFound() throws Exception {

        CoverLetter others = draftedLetter(othersUserId);

        mvc.perform(get("/api/cover-letters/{id}/turns", others.getPublicId()).with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        open(others).andExpect(status().isNotFound());
        reply(others, "로그를 봤어요").andExpect(status().isNotFound());
        skip(others).andExpect(status().isNotFound());

        verifyNoInteractions(turnPort);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE outcome = 'DENIED'", Integer.class))
                .isEqualTo(4);

    }

    @Test
    @DisplayName("호출 한도를 넘으면 429 RATE_LIMITED 이고 AI 를 부르지도 말을 저장하지도 않는다")
    void rateLimitedTurnDoesNotCallAi() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        doThrow(new RateLimitExceededException(30)).when(rateLimiter)
                .acquire(RateLimitedAction.COVER_LETTER_TURN, myUserId, 1);

        reply(letter, "로그를 봤어요")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verifyNoInteractions(turnPort);
        assertThat(turns.count()).isZero();

    }

    @Test
    @DisplayName("AI 가 잠시 응답하지 못하면 503 AI_UNAVAILABLE 이고, 사용자의 말도 저장하지 않아 다시 보내도 두 번 남지 않는다")
    void aiUnavailableSavesNothing() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenThrow(new AgentUnavailableException("AI 자소서 대화 다음 말 실패", true, false, null));

        reply(letter, "로그를 봤어요")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

        assertThat(turns.count()).isZero();

    }

    @Test
    @DisplayName("AI 응답이 계약과 달라 다시 해도 같으면 500 INTERNAL_ERROR 이고 말을 저장하지 않는다")
    void invalidAiResponseIsInternalError() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(turnPort.next(any())).thenThrow(new AgentUnavailableException("AI 자소서 대화 다음 말 실패", false, true, null));

        reply(letter, "로그를 봤어요")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(turns.count()).isZero();

    }

    private CoverLetter draftedLetter(Long userId) {

        Long card = confirmedCard(userId);
        CoverLetter letter = letters.save(CoverLetter.start(userId, "백엔드 개발", null, null));
        CoverLetterQuestion first = CoverLetterQuestion.of(letter.getId(), 1, "협업 경험을 쓰세요", 700);
        first.assign(QuestionTheme.COLLABORATION, card, null, AssignedBy.SUB_AGENT, "함께 고친 경험", List.of());
        first.asked(List.of(new MaterialAsk(MaterialItem.ACTION, "직접 한 일은?", true, List.of())),
                List.of(new MaterialAnswer(MaterialItem.ACTION, "옛 행동 답", false, false),
                        new MaterialAnswer(MaterialItem.RESULT, "배포가 빨라졌다", false, false)));
        first.drafted(AI_TEXT, List.of(new DraftSentence(1, "배포를 함께 고쳤다."), new DraftSentence(2, BLANK)), 11, 0,
                List.of(new DraftMark(1, "INFERRED", null)), List.of(MaterialItem.ACTION), DraftSource.LLM);
        questions.save(first);
        CoverLetterQuestion second = CoverLetterQuestion.of(letter.getId(), 2, "지원 동기를 쓰세요", 700);
        second.drafted("결제를 다뤄 보고 싶다.", List.of(new DraftSentence(1, "결제를 다뤄 보고 싶다.")), 12, 0,
                List.of(), List.of(), DraftSource.LLM);
        questions.save(second);
        letter.claim();
        letter.assigned(List.of());
        letter.startAsking();
        letter.claim();
        letter.asked();
        letter.startDrafting();
        letter.claim();
        letter.drafted(List.of());
        return letters.save(letter);

    }

    private CoverLetter askedLetter() {

        CoverLetter letter = letters.save(CoverLetter.start(myUserId, "백엔드 개발", null, null));
        questions.save(CoverLetterQuestion.of(letter.getId(), 1, "협업 경험을 쓰세요", 700));
        letter.claim();
        letter.assigned(List.of());
        letter.startAsking();
        letter.claim();
        letter.asked();
        return letters.save(letter);

    }

    private Long confirmedCard(Long userId) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(card, 1, "A", "빌드를 병렬로 돌렸다", "USER_STATED", null);
        fixtures.confirmCard(card);
        return card;

    }

    private void withPolish(CoverLetter letter) {

        CoverLetterQuestion question = firstQuestion(letter);
        question.polished(new QuestionPolish("결과를 더 적으면 좋습니다.", List.of(),
                new PolishedDraft("옛 글로 고친 글이다.", List.of(new DraftSentence(1, "옛 글로 고친 글이다.")), 10, 0,
                        List.of(), List.of()),
                Instant.parse("2026-11-03T01:02:03.456789Z")));
        questions.save(question);

    }

    private CoverLetterTurnRequest sentTurn() {

        ArgumentCaptor<CoverLetterTurnRequest> sent = ArgumentCaptor.forClass(CoverLetterTurnRequest.class);
        verify(turnPort).next(sent.capture());
        return sent.getValue();

    }

    private CoverLetterQuestion firstQuestion(CoverLetter letter) {

        return questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();

    }

    private UUID questionIdOf(CoverLetter letter, int seq) {

        return questions.findByCoverLetterIdOrderBySeq(letter.getId()).get(seq - 1).getPublicId();

    }

    private ResultActions open(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/turns/open", letter.getPublicId(),
                questionIdOf(letter, 1)).with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions reply(CoverLetter letter, String text) throws Exception {

        Map<String, String> body = new HashMap<>();
        body.put("text", text);
        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/turns", letter.getPublicId(),
                questionIdOf(letter, 1)).with(loggedInAs(myUserId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));

    }

    private ResultActions skip(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/turns/skip", letter.getPublicId(),
                questionIdOf(letter, 1)).with(loggedInAs(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
