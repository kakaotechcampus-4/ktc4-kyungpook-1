package com.gitory.backend.coverletter.api;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAppend;
import com.gitory.backend.agent.port.CoverLetterAppendPort;
import com.gitory.backend.agent.port.CoverLetterAppendRequest;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsPort;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
import com.gitory.backend.agent.port.CoverLetterSuggestionPort;
import com.gitory.backend.agent.port.CoverLetterSuggestionRequest;
import com.gitory.backend.agent.port.CoverLetterSuggestions;
import com.gitory.backend.agent.port.CoverLetterTurnPort;
import com.gitory.backend.agent.port.CoverLetterTurnReply;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.AssignedBy;
import com.gitory.backend.coverletter.domain.CoverLetter;
import com.gitory.backend.coverletter.domain.CoverLetterQuestion;
import com.gitory.backend.coverletter.domain.CoverLetterTurn;
import com.gitory.backend.coverletter.domain.DraftSentence;
import com.gitory.backend.coverletter.domain.DraftSource;
import com.gitory.backend.coverletter.domain.MaterialItem;
import com.gitory.backend.coverletter.domain.QuestionPolish;
import com.gitory.backend.coverletter.domain.PolishedDraft;
import com.gitory.backend.coverletter.domain.QuestionTheme;
import com.gitory.backend.coverletter.domain.Suggestion;
import com.gitory.backend.coverletter.domain.SuggestionSource;
import com.gitory.backend.coverletter.domain.TurnStatus;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.coverletter.infra.CoverLetterTurnRepository;
import com.gitory.backend.recommend.domain.UnusedCandidate;
import com.gitory.backend.recommend.domain.UnusedCandidateReader;
import com.gitory.backend.support.CandidateFixtures;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class CoverLetterSuggestApiTest {

    private static final String OLD_TEXT = "옛 글이다.";
    private static final CoverLetterTurnReply ASKING = new CoverLetterTurnReply(
            new CoverLetterTurnReply.Message("그때 직접 한 일을 한 줄만 알려 주세요.", 1, "ACTION"), null, "ASKING");
    private static final String EDITED_TEXT = "손으로 고친 글이다.";
    private static final CoverLetterDrafts.Draft APPENDED = new CoverLetterDrafts.Draft(1, EDITED_TEXT + " 응답이 빨라졌다.",
            List.of(new CoverLetterDrafts.Sentence(1, EDITED_TEXT), new CoverLetterDrafts.Sentence(2, "응답이 빨라졌다.")),
            20, 0, List.of(), List.of(), "LLM");
    private static final CoverLetterDrafts REWRITTEN = new CoverLetterDrafts(List.of(new CoverLetterDrafts.Draft(1,
            "캐시를 함께 고쳤다. 응답이 빨라졌다.",
            List.of(new CoverLetterDrafts.Sentence(1, "캐시를 함께 고쳤다."),
                    new CoverLetterDrafts.Sentence(2, "응답이 빨라졌다.")),
            20, 0, List.of(), List.of(), "LLM")), List.of());

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

    @Autowired
    UnusedCandidateReader unusedCandidates;

    @MockitoBean
    CoverLetterSuggestionPort suggestionPort;

    @MockitoBean
    CoverLetterTurnPort turnPort;

    @MockitoBean
    CoverLetterDraftsPort draftsPort;

    @MockitoBean
    CoverLetterAppendPort appendPort;

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
        when(turnPort.next(any())).thenReturn(ASKING);

    }

    @Test
    @DisplayName("중심 카드가 없고 제안이 비어 있는 문항을 열면 AI 를 한 번 불러 고른 카드를 제목·문구와 함께 저장하고 응답에 싣는다")
    void openStoresSuggestionsForAQuestionWithoutACard() throws Exception {

        UUID cardId = UUID.randomUUID();
        Long card = confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(null);
        when(suggestionPort.suggest(any())).thenReturn(new CoverLetterSuggestions(
                List.of(new CoverLetterSuggestions.Item("CARD", String.valueOf(card), "캐시를 고친 과정을 쓸 수 있어요"))));

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions.length()").value(1))
                .andExpect(jsonPath("$.data.question.suggestions[0].source").value("CARD"))
                .andExpect(jsonPath("$.data.question.suggestions[0].id").value(cardId.toString()))
                .andExpect(jsonPath("$.data.question.suggestions[0].title").value("카드"))
                .andExpect(jsonPath("$.data.question.suggestions[0].angle").value("캐시를 고친 과정을 쓸 수 있어요"))
                .andExpect(jsonPath("$.data.question.suggestions[0].repoId").value(nullValue()))
                .andExpect(jsonPath("$.data.turns.length()").value(1));

        CoverLetterSuggestionRequest sent = sentSuggestion();
        assertThat(sent.question().questionId()).isEqualTo(1);
        assertThat(sent.cards()).extracting(CoverLetterSuggestionRequest.Card::cardId).containsExactly(card);
        assertThat(sent.candidates()).isEmpty();
        assertThat(firstQuestion(letter).getSuggestions()).containsExactly(
                new Suggestion(SuggestionSource.CARD, cardId.toString(), "카드", "캐시를 고친 과정을 쓸 수 있어요", null));
        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER_SUGGEST, myUserId, 1);

    }

    @Test
    @DisplayName("이 자소서의 다른 문항이 이미 쓰는 카드는 AI 에 보내지 않고, 안 쓴 확정 카드와 새 후보만 보낸다")
    void sendsOnlyUnusedCardsAndNewCandidates() throws Exception {

        Long used = confirmedCard(myUserId, UUID.randomUUID());
        Long unused = confirmedCard(myUserId, UUID.randomUUID());
        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertRepository(100L, "grow22", "gitory");
        Long userRepo = fixtures.insertUserRepository(repoPublicId, myUserId, repo);
        Long candidate = new CandidateFixtures(jdbc).insertCandidate(userRepo, "PR", 42, "NEW");
        jdbc.update("UPDATE candidate SET review_comment_count = 9 WHERE id = ?", candidate);
        UUID candidatePublicId = jdbc.queryForObject("SELECT public_id FROM candidate WHERE id = ?", UUID.class,
                candidate);
        CoverLetter letter = letterWithFirstCard(null);
        CoverLetterQuestion second = questions.findByCoverLetterIdOrderBySeq(letter.getId()).get(1);
        second.assign(QuestionTheme.GROWTH, used, null, AssignedBy.SUB_AGENT, "이유", List.of());
        questions.save(second);
        when(suggestionPort.suggest(any())).thenReturn(new CoverLetterSuggestions(
                List.of(new CoverLetterSuggestions.Item("CANDIDATE", candidatePublicId.toString(), "리뷰를 맞춘 과정이에요"))));

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions[0].source").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.question.suggestions[0].id").value(candidatePublicId.toString()))
                .andExpect(jsonPath("$.data.question.suggestions[0].title").value("후보"))
                .andExpect(jsonPath("$.data.question.suggestions[0].repoId").value(repoPublicId.toString()));

        CoverLetterSuggestionRequest sent = sentSuggestion();
        assertThat(sent.cards()).extracting(CoverLetterSuggestionRequest.Card::cardId).containsExactly(unused);
        assertThat(sent.candidates()).hasSize(1);
        assertThat(sent.candidates().getFirst().candidateId()).isEqualTo(candidatePublicId.toString());
        assertThat(sent.candidates().getFirst().repoName()).isEqualTo("grow22/gitory");
        assertThat(sent.candidates().getFirst().reviewCommentCount()).isEqualTo(9);

    }

    @Test
    @DisplayName("중심 카드가 이미 있는 문항을 열면 제안을 묻지 않고 응답의 제안은 null 이다")
    void openNeverAsksForAQuestionWithACard() throws Exception {

        Long card = confirmedCard(myUserId, UUID.randomUUID());
        confirmedCard(myUserId, UUID.randomUUID());
        CoverLetter letter = letterWithFirstCard(card);

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions").value(nullValue()));

        verifyNoInteractions(suggestionPort);
        verify(rateLimiter, never()).acquire(RateLimitedAction.COVER_LETTER_SUGGEST, myUserId, 1);

    }

    @Test
    @DisplayName("제안이 이미 저장된 문항을 다시 열면 AI 를 다시 부르지 않고 저장한 제안을 그대로 보인다")
    void openDoesNotAskAgainWhenSuggestionsAreStored() throws Exception {

        confirmedCard(myUserId, UUID.randomUUID());
        CoverLetter letter = letterWithFirstCard(null);
        CoverLetterQuestion first = firstQuestion(letter);
        first.suggest(List.of());
        questions.save(first);

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions.length()").value(0));

        verifyNoInteractions(suggestionPort);

    }

    @Test
    @DisplayName("제안을 받지 못해도 문항 열기는 성공하고 제안은 null 로 남아 다음에 다시 묻는다")
    void openSucceedsWhenTheSuggestionCallFails() throws Exception {

        confirmedCard(myUserId, UUID.randomUUID());
        CoverLetter letter = letterWithFirstCard(null);
        when(suggestionPort.suggest(any())).thenThrow(new AgentUnavailableException("AI 자소서 제안 실패", true, false,
                new RuntimeException("x")));

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions").value(nullValue()))
                .andExpect(jsonPath("$.data.turns.length()").value(1));

        assertThat(firstQuestion(letter).getSuggestions()).isNull();

    }

    @Test
    @DisplayName("보낼 카드도 후보도 없으면 AI 를 부르지 않고 제안을 null 로 둔다")
    void openSkipsTheCallWhenThereIsNothingToSend() throws Exception {

        CoverLetter letter = letterWithFirstCard(null);

        open(letter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question.suggestions").value(nullValue()));

        verifyNoInteractions(suggestionPort);

    }

    @Test
    @DisplayName("후보를 읽으면 후보에 든 리뷰 댓글 수와 후보가 속한 저장소 연결 id 를 함께 돌려준다")
    void unusedCandidatesCarryTheReviewCommentCount() {

        Long repo = fixtures.insertRepository(100L, "grow22", "gitory");
        Long userRepo = fixtures.insertUserRepository(myUserId, repo);
        Long candidate = new CandidateFixtures(jdbc).insertCandidate(userRepo, "PR", 42, "NEW");
        jdbc.update("UPDATE candidate SET review_comment_count = 9 WHERE id = ?", candidate);

        List<UnusedCandidate> found = unusedCandidates.of(List.of(userRepo), 10, 5);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().reviewCommentCount()).isEqualTo(9);
        assertThat(found.getFirst().userRepositoryId()).isEqualTo(userRepo);

    }

    @Test
    @DisplayName("내 확정 카드로 다시 쓰면 그 카드가 중심 카드가 되고 초안이 새 글로 바뀌며 '다시 썼어요' AI 말이 대화에 더해진다")
    void rewriteReplacesTheDraftAndAppendsADoneTurn() throws Exception {

        UUID cardId = UUID.randomUUID();
        Long card = confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(null);
        CoverLetterQuestion first = firstQuestion(letter);
        first.polished(new QuestionPolish("요약", List.of(), new PolishedDraft("첨삭 글이다.",
                List.of(new DraftSentence(1, "첨삭 글이다.")), 6, 0, List.of(), List.of()),
                Instant.parse("2026-11-03T01:02:03Z")));
        questions.save(first);
        turns.save(CoverLetterTurn.ai(letter.getId(), 1, 1, "앞 말이에요.", MaterialItem.ACTION, TurnStatus.ASKING));
        when(draftsPort.write(any())).thenReturn(REWRITTEN);

        rewrite(letter, cardId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(false))
                .andExpect(jsonPath("$.data.question.primaryCard.id").value(cardId.toString()))
                .andExpect(jsonPath("$.data.question.assignedBy").value("USER"))
                .andExpect(jsonPath("$.data.question.draft.text").value("캐시를 함께 고쳤다. 응답이 빨라졌다."))
                .andExpect(jsonPath("$.data.question.draft.aiText").value("캐시를 함께 고쳤다. 응답이 빨라졌다."))
                .andExpect(jsonPath("$.data.question.draft.edited").value(false))
                .andExpect(jsonPath("$.data.question.polish").value(nullValue()))
                .andExpect(jsonPath("$.data.turns.length()").value(2))
                .andExpect(jsonPath("$.data.turns[1].seq").value(2))
                .andExpect(jsonPath("$.data.turns[1].role").value("AI"))
                .andExpect(jsonPath("$.data.turns[1].text").value("Q1 을 '카드' 카드로 다시 썼어요"))
                .andExpect(jsonPath("$.data.turns[1].status").value("DONE"))
                .andExpect(jsonPath("$.data.turns[1].item").value(nullValue()));

        ArgumentCaptor<CoverLetterDraftsRequest> sent = ArgumentCaptor.forClass(CoverLetterDraftsRequest.class);
        verify(draftsPort).write(sent.capture());
        assertThat(sent.getValue().questions()).hasSize(1);
        assertThat(sent.getValue().questions().getFirst().questionId()).isEqualTo(1);
        assertThat(sent.getValue().questions().getFirst().primaryCard().cardId()).isEqualTo(card);
        assertThat(firstQuestion(letter).getPrimaryCardId()).isEqualTo(card);

    }

    @Test
    @DisplayName("중심 카드가 있는 문항은 지금 글에 새 카드를 보조로 덧붙이며, 손으로 고친 글을 보내고 옛 문장이 남은 글로 바뀐다")
    void rewriteAppendsTheNewCardToTheCurrentTextOfAPrimaryCardQuestion() throws Exception {

        Long primary = confirmedCard(myUserId, UUID.randomUUID());
        UUID cardId = UUID.randomUUID();
        Long added = confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(primary);
        CoverLetterQuestion first = firstQuestion(letter);
        first.edit(EDITED_TEXT);
        questions.save(first);
        when(appendPort.append(any())).thenReturn(new CoverLetterAppend(APPENDED, false));

        rewrite(letter, cardId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(false))
                .andExpect(jsonPath("$.data.question.draft.text").value(EDITED_TEXT + " 응답이 빨라졌다."))
                .andExpect(jsonPath("$.data.turns[0].text").value("Q1 에 '카드' 카드 내용을 더해 썼어요"));

        ArgumentCaptor<CoverLetterAppendRequest> sent = ArgumentCaptor.forClass(CoverLetterAppendRequest.class);
        verify(appendPort).append(sent.capture());
        assertThat(sent.getValue().target().currentText()).isEqualTo(EDITED_TEXT);
        assertThat(sent.getValue().target().primaryCard().cardId()).isEqualTo(primary);
        assertThat(sent.getValue().target().supportingCard().cardId()).isEqualTo(added);
        assertThat(sent.getValue().addedCardId()).isEqualTo(added);
        assertThat(firstQuestion(letter).getPrimaryCardId()).isEqualTo(primary);
        assertThat(firstQuestion(letter).getSupportingCardId()).isEqualTo(added);
        verifyNoInteractions(draftsPort);

    }

    @Test
    @DisplayName("덧붙이기가 되돌려지면 글과 카드를 바꾸지 않고 못 더했다고 말한다")
    void revertedAppendKeepsTheTextAndSaysItCouldNotAdd() throws Exception {

        Long primary = confirmedCard(myUserId, UUID.randomUUID());
        UUID cardId = UUID.randomUUID();
        confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(primary);
        when(appendPort.append(any())).thenReturn(new CoverLetterAppend(
                new CoverLetterDrafts.Draft(1, OLD_TEXT, List.of(new CoverLetterDrafts.Sentence(1, OLD_TEXT)), 6, 0,
                        List.of(), List.of(), "LLM"), true));

        rewrite(letter, cardId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(true))
                .andExpect(jsonPath("$.data.question.draft.text").value(OLD_TEXT))
                .andExpect(jsonPath("$.data.turns[0].text").value("Q1 에 '카드' 카드 내용을 더하지 못했어요"));

        assertThat(firstQuestion(letter).getDraftText()).isEqualTo(OLD_TEXT);
        assertThat(firstQuestion(letter).getSupportingCardId()).isNull();

    }

    @Test
    @DisplayName("확정하지 않은 카드나 남의 카드로는 다시 쓸 수 없고, 400 이며 AI 도 부르지 않고 문항도 그대로다")
    void rewriteRejectsACardThatIsNotMyConfirmedCard() throws Exception {

        UUID draftCard = UUID.randomUUID();
        fixtures.insertCard(draftCard, myUserId);
        UUID othersCard = UUID.randomUUID();
        confirmedCard(othersUserId, othersCard);
        CoverLetter letter = letterWithFirstCard(null);

        rewrite(letter, draftCard.toString()).andExpect(status().isBadRequest());
        rewrite(letter, othersCard.toString()).andExpect(status().isBadRequest());
        rewrite(letter, "not-a-uuid").andExpect(status().isBadRequest());

        verifyNoInteractions(draftsPort);
        verify(rateLimiter, never()).acquire(RateLimitedAction.COVER_LETTER_REWRITE, myUserId, 1);
        assertThat(firstQuestion(letter).getPrimaryCardId()).isNull();
        assertThat(firstQuestion(letter).getDraftText()).isEqualTo(OLD_TEXT);

    }

    @Test
    @DisplayName("다시 쓰는 중 AI 가 응답하지 못하면 카드도 글도 대화도 바꾸지 않고 rewriteFailed 가 true 다")
    void rewriteChangesNothingWhenTheAiFails() throws Exception {

        UUID cardId = UUID.randomUUID();
        confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(null);
        when(draftsPort.write(any())).thenThrow(new AgentUnavailableException("AI 자소서 초안 쓰기 실패", true, false,
                new RuntimeException("x")));

        rewrite(letter, cardId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewriteFailed").value(true))
                .andExpect(jsonPath("$.data.question.primaryCard").value(nullValue()))
                .andExpect(jsonPath("$.data.question.draft.text").value(OLD_TEXT))
                .andExpect(jsonPath("$.data.turns.length()").value(0));

        assertThat(firstQuestion(letter).getPrimaryCardId()).isNull();
        assertThat(firstQuestion(letter).getDraftText()).isEqualTo(OLD_TEXT);
        assertThat(turns.findByCoverLetterIdOrderBySeq(letter.getId())).isEmpty();

    }

    @Test
    @DisplayName("다시 쓰기는 AI 를 부를 때 호출 제한을 한 번 센다")
    void rewriteCountsTheRateLimit() throws Exception {

        UUID cardId = UUID.randomUUID();
        confirmedCard(myUserId, cardId);
        CoverLetter letter = letterWithFirstCard(null);
        when(draftsPort.write(any())).thenReturn(REWRITTEN);

        rewrite(letter, cardId.toString()).andExpect(status().isOk());

        verify(rateLimiter).acquire(RateLimitedAction.COVER_LETTER_REWRITE, myUserId, 1);

    }

    private CoverLetter letterWithFirstCard(Long primaryCardId) {

        CoverLetter letter = letters.save(CoverLetter.start(myUserId, "백엔드 개발", null, null));
        CoverLetterQuestion first = CoverLetterQuestion.of(letter.getId(), 1, "협업 경험을 쓰세요", 700);
        first.assign(QuestionTheme.COLLABORATION, primaryCardId, null, AssignedBy.SUB_AGENT, null, List.of());
        first.drafted(OLD_TEXT, List.of(new DraftSentence(1, OLD_TEXT)), 6, 0, List.of(), List.of(),
                DraftSource.LLM);
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

    private Long confirmedCard(Long userId, UUID publicId) {

        Long card = fixtures.insertCard(publicId, userId);
        fixtures.insertStatement(card, 1, "A", "캐시를 바꿔 응답을 줄였다", "USER_STATED", null);
        fixtures.confirmCard(card);
        return card;

    }

    private CoverLetterSuggestionRequest sentSuggestion() {

        ArgumentCaptor<CoverLetterSuggestionRequest> sent = ArgumentCaptor.forClass(CoverLetterSuggestionRequest.class);
        verify(suggestionPort).suggest(sent.capture());
        return sent.getValue();

    }

    private CoverLetterQuestion firstQuestion(CoverLetter letter) {

        return questions.findByCoverLetterIdOrderBySeq(letter.getId()).getFirst();

    }

    private ResultActions open(CoverLetter letter) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/turns/open", letter.getPublicId(),
                firstQuestion(letter).getPublicId()).with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions rewrite(CoverLetter letter, String cardId) throws Exception {

        return mvc.perform(post("/api/cover-letters/{id}/questions/{qid}/rewrite", letter.getPublicId(),
                firstQuestion(letter).getPublicId()).with(loggedInAs(myUserId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + cardId + "\"}"));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
