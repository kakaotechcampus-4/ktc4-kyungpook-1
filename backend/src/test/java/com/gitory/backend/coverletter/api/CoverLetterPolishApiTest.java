package com.gitory.backend.coverletter.api;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterPolish;
import com.gitory.backend.agent.port.CoverLetterPolishPort;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.CoverLetter;
import com.gitory.backend.coverletter.domain.CoverLetterQuestion;
import com.gitory.backend.coverletter.domain.DraftMark;
import com.gitory.backend.coverletter.domain.DraftSentence;
import com.gitory.backend.coverletter.domain.DraftSource;
import com.gitory.backend.coverletter.domain.MaterialItem;
import com.gitory.backend.coverletter.domain.PolishCategory;
import com.gitory.backend.coverletter.domain.PolishKind;
import com.gitory.backend.coverletter.domain.PolishPoint;
import com.gitory.backend.coverletter.domain.PolishedDraft;
import com.gitory.backend.coverletter.domain.QuestionPolish;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
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

import java.time.Instant;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class CoverLetterPolishApiTest {

    private static final String AI_TEXT = "원인을 찾았다. 배웠다.";
    private static final String EDITED_TEXT = "원인을 끝까지 찾았다. 배웠다.";
    private static final CoverLetterPolish POLISHED = new CoverLetterPolish("과정이 잘 보입니다.",
            List.of(new CoverLetterPolish.Point(1, "WEAK", "EVIDENCE", "결과가 안 보여요", "달라진 점을 적어요", List.of(2)),
                    new CoverLetterPolish.Point(2, "GOOD", "FIT", "끝까지 파고들어요", "문항과 맞아요", List.of(1))),
            new CoverLetterDrafts.Draft(1, "원인을 끝까지 찾았다. 응답이 빨라졌다.",
                    List.of(new CoverLetterDrafts.Sentence(1, "원인을 끝까지 찾았다."),
                            new CoverLetterDrafts.Sentence(2, "응답이 빨라졌다.")),
                    21, 0, List.of(new CoverLetterDrafts.Mark(2, "INFERRED", null)), List.of(), "LLM"));

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

    @MockitoBean
    CoverLetterPolishPort polishPort;

    @MockitoBean
    UserRateLimiter rateLimiter;

    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "taehun0208");

    }

    @Test
    @DisplayName("첨삭을 받으면 진단과 고친 글을 문항의 polish 에 두고 지금 글은 그대로 둔 자소서를 돌려주며, 호출 제한을 한 번 센다")
    void polishStoresPreviewWithoutChangingTheDraft() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(polishPort.polish(any())).thenReturn(POLISHED);

        polish(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].draft.text").value(EDITED_TEXT))
                .andExpect(jsonPath("$.data.questions[0].polishAppliedCount").value(0))
                .andExpect(jsonPath("$.data.questions[0].polish.summary").value("과정이 잘 보입니다."))
                .andExpect(jsonPath("$.data.questions[0].polish.points[0].kind").value("WEAK"))
                .andExpect(jsonPath("$.data.questions[0].polish.points[0].category").value("EVIDENCE"))
                .andExpect(jsonPath("$.data.questions[0].polish.points[0].sentenceSeqs[0]").value(2))
                .andExpect(jsonPath("$.data.questions[0].polish.draft.text").value("원인을 끝까지 찾았다. 응답이 빨라졌다."))
                .andExpect(jsonPath("$.data.questions[0].polish.draft.marks[0].code").value("INFERRED"))
                .andExpect(jsonPath("$.data.questions[0].polish.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.questions[1].polish").value(nullValue()));

        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER_POLISH, myUserId, 1);

    }

    @Test
    @DisplayName("첨삭 요청에는 사용자가 고친 지금 글과 다른 문항의 지금 글, 아직 확인하지 않은 추론 표시의 문장 번호를 싣는다")
    void requestCarriesCurrentTextsAndUnconfirmedInferences() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(polishPort.polish(any())).thenReturn(POLISHED);

        polish(letter).andExpect(status().isOk());

        ArgumentCaptor<CoverLetterPolishRequest> sent = ArgumentCaptor.forClass(CoverLetterPolishRequest.class);
        verify(polishPort).polish(sent.capture());
        assertThat(sent.getValue().coverLetterId()).isEqualTo(letter.getId());
        assertThat(sent.getValue().target().questionId()).isEqualTo(1);
        assertThat(sent.getValue().target().currentText()).isEqualTo(EDITED_TEXT);
        assertThat(sent.getValue().target().inferredSeqs()).containsExactly(2);
        assertThat(sent.getValue().others())
                .containsExactly(new CoverLetterPolishRequest.Other(2, "지원 동기를 쓰세요", "결제를 다뤄 보고 싶다."));

    }

    @Test
    @DisplayName("지금 글이 비었으면 400 이고 AI 를 부르지도 호출 제한을 세지도 않는다")
    void rejectsBlankDraft() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        question.edit(" ");
        questions.save(question);

        polish(letter).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(polishPort);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    @Test
    @DisplayName("적용하면 지금 글·문장·표시·빈칸·글자 수를 첨삭한 글로 바꾸고 고친 적 없음으로 두며, 적용 횟수를 1 늘리고 첨삭 결과를 비우고 AI 원본은 그대로 둔다")
    void applyReplacesTheDraftAndKeepsAiText() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        withPolish(letter);

        apply(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].draft.text").value("고친 글이다. 결과가 나왔다."))
                .andExpect(jsonPath("$.data.questions[0].draft.aiText").value(AI_TEXT))
                .andExpect(jsonPath("$.data.questions[0].draft.edited").value(false))
                .andExpect(jsonPath("$.data.questions[0].draft.charCount").value(15))
                .andExpect(jsonPath("$.data.questions[0].draft.overBy").value(0))
                .andExpect(jsonPath("$.data.questions[0].draft.sentences.length()").value(2))
                .andExpect(jsonPath("$.data.questions[0].draft.sentences[1].text").value("결과가 나왔다."))
                .andExpect(jsonPath("$.data.questions[0].draft.marks.length()").value(1))
                .andExpect(jsonPath("$.data.questions[0].draft.marks[0].code").value("UNSOURCED_NUMBER"))
                .andExpect(jsonPath("$.data.questions[0].draft.blanks[0]").value("RESULT"))
                .andExpect(jsonPath("$.data.questions[0].polishAppliedCount").value(1))
                .andExpect(jsonPath("$.data.questions[0].polish").value(nullValue()));

        CoverLetterQuestion saved = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        assertThat(saved.getAiText()).isEqualTo(AI_TEXT);
        assertThat(saved.getPolish()).isNull();
        assertThat(saved.getPolishAppliedCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("버리면 첨삭 결과만 비우고 지금 글은 그대로 두며, 결과가 없을 때 다시 버려도 200 이다")
    void discardClearsOnlyThePolish() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        withPolish(letter);

        discard(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].polish").value(nullValue()))
                .andExpect(jsonPath("$.data.questions[0].draft.text").value(EDITED_TEXT))
                .andExpect(jsonPath("$.data.questions[0].polishAppliedCount").value(0));
        discard(letter).andExpect(status().isOk());

    }

    @Test
    @DisplayName("첨삭을 받은 뒤 글을 직접 고쳐 저장하면 첨삭 결과를 비우고, 그 뒤 적용하면 409 이고 고친 글이 남는다")
    void editAfterPolishDropsThePolish() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        withPolish(letter);

        editDraft(letter, "{\"text\": \"직접 고친 글이다.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].draft.text").value("직접 고친 글이다."))
                .andExpect(jsonPath("$.data.questions[0].polish").value(nullValue()));
        apply(letter).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        CoverLetterQuestion saved = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        assertThat(saved.getDraftText()).isEqualTo("직접 고친 글이다.");
        assertThat(saved.getPolishAppliedCount()).isZero();

    }

    @Test
    @DisplayName("첨삭을 받은 뒤 같은 글로 저장하면 첨삭 결과를 그대로 둔다")
    void editWithSameTextKeepsThePolish() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        QuestionPolish polish = withPolish(letter);

        editDraft(letter, "{\"text\": \"" + EDITED_TEXT + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].polish.draft.text").value("고친 글이다. 결과가 나왔다."));

        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPolish()).isEqualTo(polish);

    }

    @Test
    @DisplayName("초안을 다 쓰기 전에는 첨삭 받기·적용·버리기가 409 이고, AI 를 부르지도 호출 제한을 세지도 않는다")
    void rejectsBeforeDrafted() throws Exception {

        CoverLetter asked = askedLetter();

        polish(asked).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        apply(asked).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        discard(asked).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        verifyNoInteractions(polishPort);
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    @Test
    @DisplayName("첨삭 결과가 없는데 적용하면 409 이고 글과 적용 횟수는 그대로다")
    void rejectsApplyWithoutPolish() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);

        apply(letter).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        CoverLetterQuestion saved = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        assertThat(saved.getDraftText()).isEqualTo(EDITED_TEXT);
        assertThat(saved.getPolishAppliedCount()).isZero();

    }

    @Test
    @DisplayName("남의 자소서는 첨삭 받기·적용·버리기 모두 404 이고, AI 를 부르지 않고 첨삭 결과도 그대로 두며 거절로 남긴다")
    void othersLetterIsNotFound() throws Exception {

        CoverLetter others = draftedLetter(othersUserId);
        withPolish(others);

        polish(others).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        apply(others).andExpect(status().isNotFound());
        discard(others).andExpect(status().isNotFound());

        verifyNoInteractions(polishPort);
        assertThat(questions.findByCoverLetterIdOrderBySeq(others.getId()).getFirst().getPolish()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'EDIT_COVER_LETTER' AND outcome = 'DENIED'",
                Integer.class)).isEqualTo(3);

    }

    @Test
    @DisplayName("호출 한도를 넘으면 429 RATE_LIMITED 이고 AI 를 부르지 않는다")
    void rateLimitedPolishDoesNotCallAi() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        doThrow(new RateLimitExceededException(30)).when(rateLimiter)
                .acquire(RateLimitedAction.COVER_LETTER_POLISH, myUserId, 1);

        polish(letter)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(polishPort, never()).polish(any());

    }

    @Test
    @DisplayName("AI 가 잠시 응답하지 못하면 503 AI_UNAVAILABLE 이고 앞 첨삭 결과를 지우지도 덮지도 않는다")
    void aiUnavailableKeepsThePreviousPolish() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        QuestionPolish previous = withPolish(letter);
        when(polishPort.polish(any())).thenThrow(new AgentUnavailableException("AI 자소서 첨삭 실패", true, false, null));

        polish(letter)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

        assertThat(questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPolish()).isEqualTo(previous);

    }

    @Test
    @DisplayName("AI 응답이 계약과 달라 다시 해도 같으면 500 INTERNAL_ERROR 다")
    void invalidAiResponseIsInternalError() throws Exception {

        CoverLetter letter = draftedLetter(myUserId);
        when(polishPort.polish(any())).thenThrow(new AgentUnavailableException("AI 자소서 첨삭 실패", false, true, null));

        polish(letter)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

    }

    private CoverLetter draftedLetter(Long userId) {

        CoverLetter letter = letters.save(CoverLetter.start(userId, "백엔드 개발", null, null));
        CoverLetterQuestion first = CoverLetterQuestion.of(letter.getId(), 1, "어려운 문제를 해결한 경험을 쓰세요", 100);
        first.drafted(AI_TEXT, List.of(new DraftSentence(1, "원인을 찾았다."), new DraftSentence(2, "배웠다.")), 12, 0,
                List.of(new DraftMark(1, "UNSOURCED_TERM", "원인"), new DraftMark(2, "INFERRED", null)), List.of(),
                DraftSource.LLM);
        first.edit(EDITED_TEXT);
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

    private QuestionPolish withPolish(CoverLetter letter) {

        CoverLetterQuestion question = questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();
        QuestionPolish polish = new QuestionPolish("결과를 더 적으면 좋습니다.",
                List.of(new PolishPoint(1, PolishKind.WEAK, PolishCategory.EVIDENCE, "결과가 안 보여요", "한 가지 적어요",
                        List.of(2))),
                new PolishedDraft("고친 글이다. 결과가 나왔다.",
                        List.of(new DraftSentence(1, "고친 글이다."), new DraftSentence(2, "결과가 나왔다.")), 15, 0,
                        List.of(new DraftMark(2, "UNSOURCED_NUMBER", "3분")), List.of(MaterialItem.RESULT)),
                Instant.parse("2026-11-03T01:02:03.456789Z"));
        question.polished(polish);
        questions.save(question);
        return polish;

    }

    private ResultActions polish(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/polish", letter.getPublicId(),
                questionIdOf(letter)).with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions apply(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/polish/apply", letter.getPublicId(),
                questionIdOf(letter)).with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions editDraft(CoverLetter letter, String body) throws Exception {

        return mvc.perform(patch("/api/cover-letters/{id}/questions/{qid}", letter.getPublicId(), questionIdOf(letter))
                .with(loggedInAs(myUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions discard(CoverLetter letter) throws Exception {

        return mvc.perform(delete("/api/cover-letters/{id}/questions/{qid}/polish", letter.getPublicId(),
                questionIdOf(letter)).with(loggedInAs(myUserId)).with(csrf()));

    }

    private UUID questionIdOf(CoverLetter letter) {

        return questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst().getPublicId();

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
