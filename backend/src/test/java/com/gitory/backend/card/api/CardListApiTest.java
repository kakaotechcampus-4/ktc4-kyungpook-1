package com.gitory.backend.card.api;

import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class CardListApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    CardStatementRepository statements;

    @MockitoSpyBean
    InterviewTurnRepository turns;

    private TestFixtures fixtures;
    private InterviewFixtures interviews;

    private Long myUserId;
    private Long othersUserId;
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("카드 목록은 남의 카드 없이 내 카드만, 작성 중·확정 상태와 상관없이 최근에 고친 순으로 돌려준다")
    void listsOnlyMyCardsByUpdatedAtDesc() throws Exception {

        UUID january = UUID.randomUUID();
        UUID march = UUID.randomUUID();
        UUID february = UUID.randomUUID();
        updatedAt(fixtures.insertCard(january, myUserId), "2026-01-01T00:00:00Z");
        updatedAt(fixtures.insertCard(march, myUserId), "2026-03-01T00:00:00Z");
        Long confirmed = fixtures.insertCard(february, myUserId);
        fixtures.confirmCard(confirmed);
        updatedAt(confirmed, "2026-02-01T00:00:00Z");
        updatedAt(fixtures.insertCard(UUID.randomUUID(), othersUserId), "2026-04-01T00:00:00Z");

        String body = bodyOf(getCards()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data[1].status").value("CONFIRMED")));

        assertThat(idsOf(body)).containsExactly(march.toString(), february.toString(), january.toString());

    }

    @Test
    @DisplayName("고친 시각이 같은 카드는 나중에 만든 카드가 앞에 온다")
    void breaksTiesByNewerCard() throws Exception {

        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        updatedAt(fixtures.insertCard(older, myUserId), "2026-01-01T00:00:00Z");
        updatedAt(fixtures.insertCard(newer, myUserId), "2026-01-01T00:00:00Z");

        assertThat(idsOf(bodyOf(getCards()))).containsExactly(newer.toString(), older.toString());

    }

    @Test
    @DisplayName("내 카드가 없으면 남의 카드가 있어도 빈 배열을 돌려준다")
    void returnsEmptyArrayWithoutMyCards() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), othersUserId);

        getCards()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

    }

    @Test
    @DisplayName("칸 상태는 현재 버전 문장으로 판정해 행이 없으면 EMPTY, 확신도 LOW 면 NEEDS_REVIEW, MEDIUM·HIGH·확신도 없음은 FILLED 이고 옛 버전 문장은 섞지 않는다")
    void judgesStarFromCurrentVersion() throws Exception {

        Long edited = fixtures.insertCard(UUID.randomUUID(), myUserId);
        for (String slot : List.of("S", "T", "A", "R")) {
            fixtures.insertStatement(edited, 1, slot, "옛 글", "COMMIT", "LOW");
        }
        fixtures.insertCardVersion(edited, 2, "USER_EDIT", false);
        fixtures.insertStatement(edited, 2, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatement(edited, 2, "T", "과제", "COMMIT", "LOW");
        fixtures.insertStatement(edited, 2, "A", "행동", "USER_STATED", null);
        updatedAt(edited, "2026-02-01T00:00:00Z");
        Long medium = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(medium, 1, "S", "상황", "COMMIT", "MEDIUM");
        updatedAt(medium, "2026-01-01T00:00:00Z");

        getCards()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].star.S").value("FILLED"))
                .andExpect(jsonPath("$.data[0].star.T").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data[0].star.A").value("FILLED"))
                .andExpect(jsonPath("$.data[0].star.R").value("EMPTY"))
                .andExpect(jsonPath("$.data[1].star.S").value("FILLED"))
                .andExpect(jsonPath("$.data[1].star.T").value("EMPTY"))
                .andExpect(jsonPath("$.data[1].star.A").value("EMPTY"))
                .andExpect(jsonPath("$.data[1].star.R").value("EMPTY"));

    }

    @Test
    @DisplayName("근거 수는 현재 버전 문장의 커밋 연결을 모두 더해 같은 커밋이 두 칸에 붙으면 둘로 세고, 내가 쓴 문장 수는 직접 쓰거나 고른 문장 수다")
    void countsEvidenceLinksAndUserStatements() throws Exception {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long old = fixtures.insertStatement(cardId, 1, "S", "옛 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(old, firstCommit);
        fixtures.insertStatement(cardId, 1, "T", "옛 과제", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "R");
        Long situation = fixtures.insertStatement(cardId, 2, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertStatementEvidence(situation, secondCommit);
        Long task = fixtures.insertStatement(cardId, 2, "T", "과제", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(task, firstCommit);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "R", "결과", "USER_SELECTED", null, turnId);

        getCards()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].evidenceCount").value(3))
                .andExpect(jsonPath("$.data[0].userStatedCount").value(2));

    }

    @Test
    @DisplayName("근거와 내가 쓴 문장이 없는 카드도 두 개수를 0 으로 실어 보낸다")
    void sendsZeroCounts() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);

        Map<String, Object> card = JsonPath.read(bodyOf(getCards()), "$.data[0]");

        assertThat(card).containsEntry("evidenceCount", 0).containsEntry("userStatedCount", 0);

    }

    @Test
    @DisplayName("카드마다 저장된 분야·기술 태그를 순서대로 싣는다")
    void sendsTags() throws Exception {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), myUserId);
        jdbc.update("UPDATE card SET areas = '{DB,BACKEND}', techs = '{SQL,JAVA}' WHERE id = ?", cardId);

        getCards()
                .andExpect(jsonPath("$.data[0].areas").value(contains("DB", "BACKEND")))
                .andExpect(jsonPath("$.data[0].techs").value(contains("SQL", "JAVA")));

    }

    @Test
    @DisplayName("태그가 없는 카드는 분야·기술을 null 이 아닌 빈 배열로 싣는다")
    void sendsEmptyTags() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);

        getCards()
                .andExpect(jsonPath("$.data[0].areas").isArray())
                .andExpect(jsonPath("$.data[0].areas").isEmpty())
                .andExpect(jsonPath("$.data[0].techs").isArray())
                .andExpect(jsonPath("$.data[0].techs").isEmpty());

    }

    @Test
    @DisplayName("저장소에서 만든 카드는 목록에도 카드 상세와 같은 저장소(repo)를 카드마다 싣는다")
    void sendsRepositoryOfEachCardLikeDetail() throws Exception {

        UUID gitoryCard = UUID.randomUUID();
        UUID docsCard = UUID.randomUUID();
        UUID gitory = UUID.randomUUID();
        UUID docs = UUID.randomUUID();
        inRepository(fixtures.insertCard(gitoryCard, myUserId), gitory, 10L, "grow22", "gitory-web");
        inRepository(fixtures.insertCard(docsCard, myUserId), docs, 11L, "kakaotechcampus-4", "docs");

        String list = bodyOf(getCards().andExpect(status().isOk()));

        assertThat(repoInList(list, gitoryCard)).isEqualTo(repoInDetail(gitoryCard))
                .containsEntry("id", gitory.toString())
                .containsEntry("owner", "grow22")
                .containsEntry("name", "gitory-web");
        assertThat(repoInList(list, docsCard)).isEqualTo(repoInDetail(docsCard))
                .containsEntry("id", docs.toString())
                .containsEntry("owner", "kakaotechcampus-4")
                .containsEntry("name", "docs");

    }

    @Test
    @DisplayName("활동 안 카드는 카드 상세와 같은 activity(공개 id·이름·종류)를 싣고, 활동 밖 카드는 activity 가 null 이다")
    void sendsActivityOfEachCardLikeDetail() throws Exception {

        UUID clubCard = UUID.randomUUID();
        UUID outsideCard = UUID.randomUUID();
        UUID club = UUID.randomUUID();
        Long clubId = fixtures.insertActivity(club, myUserId, "교내 개발 동아리", "CLUB", "2025-03-01");
        jdbc.update("UPDATE card SET activity_id = ? WHERE id = ?", clubId, fixtures.insertCard(clubCard, myUserId));
        fixtures.insertCard(outsideCard, myUserId);

        String list = bodyOf(getCards().andExpect(status().isOk()));

        assertThat(activityInList(list, clubCard)).isEqualTo(activityInDetail(clubCard))
                .containsEntry("id", club.toString())
                .containsEntry("name", "교내 개발 동아리")
                .containsEntry("type", "CLUB");
        assertThat(activityInList(list, outsideCard)).isNull();

    }

    @Test
    @DisplayName("저장소 없이 직접 쓴 카드는 목록의 repo 를 빼지 않고 null 로 싣는다")
    void sendsNullRepositoryForManualCard() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);

        Map<String, Object> card = JsonPath.read(bodyOf(getCards().andExpect(status().isOk())), "$.data[0]");

        assertThat(card).containsEntry("repo", null);

    }

    @Test
    @DisplayName("직접 작성 카드는 출처 이름 MANUAL·후보 종류 null 이고, AI 카드는 둘 다 null 이다")
    void labelsOnlyManualCards() throws Exception {

        Long manual = fixtures.insertCard(UUID.randomUUID(), myUserId);
        updatedAt(manual, "2026-02-01T00:00:00Z");
        Long ai = fixtures.insertCard(UUID.randomUUID(), myUserId);
        jdbc.update("UPDATE card SET origin = 'AI' WHERE id = ?", ai);
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", ai);
        updatedAt(ai, "2026-01-01T00:00:00Z");

        String body = bodyOf(getCards());
        Map<String, Object> manualCard = JsonPath.read(body, "$.data[0]");
        Map<String, Object> aiCard = JsonPath.read(body, "$.data[1]");

        assertThat(manualCard).containsEntry("sourceLabel", "MANUAL").containsEntry("sourceType", null);
        assertThat(aiCard).containsEntry("sourceLabel", null).containsEntry("sourceType", null);

    }

    @Test
    @DisplayName("직접 작성 카드는 결과가 ANSWERED 인 질문이 하나라도 있으면 출처 이름이 INTERVIEW 이고, 답하지 않았거나 LATER·SKIPPED·INSUFFICIENT 인 질문뿐이면 MANUAL 이다")
    void labelsManualCardsWithAnsweredTurnsAsInterview() throws Exception {

        UUID answered = UUID.randomUUID();
        UUID answeredTwice = UUID.randomUUID();
        UUID openOnly = UUID.randomUUID();
        UUID unfinished = UUID.randomUUID();
        UUID noTurns = UUID.randomUUID();
        Long answeredCard = fixtures.insertCard(answered, myUserId);
        interviews.insertAnswered(answeredCard, 1, "S", "답", "TYPED");
        Long answeredTwiceCard = fixtures.insertCard(answeredTwice, myUserId);
        interviews.insertAnswered(answeredTwiceCard, 1, "S", "첫 답", "TYPED");
        interviews.insertTurn(answeredTwiceCard, 2, "T", "FOLLOWUP", "USER", "질문", "ANSWERED", "고른 답", "SELECTED");
        interviews.insertAsked(fixtures.insertCard(openOnly, myUserId), 1, "S", "EVIDENCE_GAP", "USER", "열린 질문");
        Long unfinishedCard = fixtures.insertCard(unfinished, myUserId);
        interviews.insertTurn(unfinishedCard, 1, "S", "EVIDENCE_GAP", "USER", "질문", "LATER", null, null);
        interviews.insertTurn(unfinishedCard, 2, "T", "EVIDENCE_GAP", "USER", "질문", "SKIPPED", null, null);
        interviews.insertTurn(unfinishedCard, 3, "A", "EVIDENCE_GAP", "USER", "질문", "INSUFFICIENT", "남은 답", "TYPED");
        fixtures.insertCard(noTurns, myUserId);

        Map<String, String> labels = labelsOf(bodyOf(getCards()));

        assertThat(labels).containsEntry(answered.toString(), "INTERVIEW")
                .containsEntry(answeredTwice.toString(), "INTERVIEW")
                .containsEntry(openOnly.toString(), "MANUAL")
                .containsEntry(unfinished.toString(), "MANUAL")
                .containsEntry(noTurns.toString(), "MANUAL");

    }

    @Test
    @DisplayName("직접 작성 카드가 아닌 카드는 답한 질문이 있어도 출처 이름이 null 이고, 다른 사람의 카드에 달린 답은 내 카드의 출처 이름에 영향을 주지 않는다")
    void keepsNullLabelOfNonManualCardsAndIgnoresOthersAnswers() throws Exception {

        UUID aiCardId = UUID.randomUUID();
        UUID myCardId = UUID.randomUUID();
        Long aiCard = fixtures.insertCard(aiCardId, myUserId);
        jdbc.update("UPDATE card SET origin = 'AI' WHERE id = ?", aiCard);
        interviews.insertAnswered(aiCard, 1, "S", "AI 카드에 한 답", "TYPED");
        fixtures.insertCard(myCardId, myUserId);
        interviews.insertAnswered(fixtures.insertCard(UUID.randomUUID(), othersUserId), 1, "S", "남의 카드에 한 답", "TYPED");

        Map<String, String> labels = labelsOf(bodyOf(getCards()));

        assertThat(labels).containsEntry(aiCardId.toString(), null).containsEntry(myCardId.toString(), "MANUAL");

    }

    @Test
    @DisplayName("답한 질문은 카드가 몇 장이든 카드 id 를 묶은 질의 한 번으로 찾고 카드마다 질문을 따로 읽지 않는다")
    void findsAnsweredTurnsOfEveryCardWithOneQuery() throws Exception {

        for (int index = 1; index <= 4; index++) {
            Long cardId = fixtures.insertCard(UUID.randomUUID(), myUserId);
            interviews.insertAnswered(cardId, 1, "S", "답", "TYPED");
        }
        clearInvocations(turns);

        getCards().andExpect(status().isOk());

        verify(turns, times(1)).findCardIdsWithAnswer(any());
        verify(turns, never()).findByCardIdOrderBySeqAsc(any());

    }

    @Test
    @DisplayName("목록 한 줄은 계약의 열다섯 키에 현재 버전 번호·기간(없으면 null)·카드를 고친 시각을 ISO 문자열로 담는다")
    void carriesVersionPeriodAndUpdatedAt() throws Exception {

        UUID publicId = UUID.randomUUID();
        Long cardId = fixtures.insertCard(publicId, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertCardVersion(cardId, 3, "USER_EDIT", false);
        jdbc.update("UPDATE card SET title = '팀 설득 경험', period = '2024년 상반기' WHERE id = ?", cardId);
        updatedAt(cardId, "2026-02-03T04:05:06.123456Z");
        updatedAt(fixtures.insertCard(UUID.randomUUID(), myUserId), "2026-01-01T00:00:00Z");

        String body = bodyOf(getCards());
        Map<String, Object> card = JsonPath.read(body, "$.data[0]");

        assertThat(card).containsOnlyKeys("id", "kind", "status", "title", "areas", "techs", "versionNo", "star",
                "updatedAt", "sourceLabel", "sourceType", "period", "evidenceCount", "userStatedCount", "repo",
                "activity");
        assertThat(card).containsEntry("id", publicId.toString())
                .containsEntry("kind", "QUALITATIVE")
                .containsEntry("status", "DRAFT")
                .containsEntry("title", "팀 설득 경험")
                .containsEntry("versionNo", 3)
                .containsEntry("period", "2024년 상반기");
        assertThat(Instant.parse((String) card.get("updatedAt"))).isEqualTo(Instant.parse("2026-02-03T04:05:06.123456Z"));
        assertThat((Map<String, Object>) JsonPath.read(body, "$.data[1]")).containsEntry("period", null);

    }

    @Test
    @DisplayName("임시 저장으로 고친 카드는 목록 맨 앞으로 온다")
    void savedCardMovesToFront() throws Exception {

        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        updatedAt(fixtures.insertCard(older, myUserId), "2026-01-01T00:00:00Z");
        updatedAt(fixtures.insertCard(newer, myUserId), "2026-02-01T00:00:00Z");

        saveSituation(older, "상황");

        assertThat(idsOf(bodyOf(getCards()))).containsExactly(older.toString(), newer.toString());

    }

    @Test
    @DisplayName("목록을 읽는 사이에 다른 요청이 그 카드를 임시 저장해도 카드·문장·근거를 한 시점으로 읽는다")
    void readsOneSnapshotWhileCardIsSaved() throws Exception {

        UUID publicId = UUID.randomUUID();
        Long cardId = fixtures.insertCard(publicId, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);

        Answer<?> real = Mockito.mockingDetails(statements).getMockCreationSettings().getDefaultAnswer();
        ExecutorService otherTab = Executors.newSingleThreadExecutor();

        try {
            // 문장을 읽은 직후·근거를 읽기 전에 다른 탭의 덮어쓰기를 커밋시켜, 옛 문장과 그 근거 연결이 지워진 뒤 근거를 읽게 한다
            doAnswer(invocation -> {
                Object found = real.answer(invocation);
                otherTab.submit(() -> saveSituation(publicId, "고친 상황")).get(30, TimeUnit.SECONDS);
                return found;
            }).when(statements).findCurrentOf(any());

            getCards()
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].star.S").value("FILLED"))
                    .andExpect(jsonPath("$.data[0].evidenceCount").value(1))
                    .andExpect(jsonPath("$.data[0].userStatedCount").value(0));
        } finally {
            otherTab.shutdownNow();
        }

        assertThat(jdbc.queryForList("SELECT evidence_type FROM card_statement WHERE card_id = ?", String.class, cardId))
                .containsExactly("USER_STATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM statement_evidence e JOIN card_statement s ON s.id = e.statement_id WHERE s.card_id = ?",
                Integer.class, cardId)).isZero();

    }

    @Test
    @DisplayName("로그인하지 않은 카드 목록 조회는 401 UNAUTHENTICATED 다")
    void listNeedsSession() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);

        mvc.perform(get("/api/cards"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    private void updatedAt(Long cardId, String instant) {

        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC), cardId);

    }

    private ResultActions getCards() throws Exception {

        return mvc.perform(get("/api/cards").with(loggedInAs(myUserId)));

    }

    private void inRepository(Long cardId, UUID connectionId, Long githubRepoId, String owner, String name) {

        Long connection = fixtures.insertUserRepository(connectionId, myUserId,
                fixtures.insertRepository(githubRepoId, owner, name));
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", connection, cardId);

    }

    private static Map<String, Object> repoInList(String body, UUID cardId) {

        List<Map<String, Object>> repos = JsonPath.read(body, "$.data[?(@.id == '" + cardId + "')].repo");

        return repos.get(0);

    }

    private static Map<String, Object> activityInList(String body, UUID cardId) {

        List<Map<String, Object>> activities = JsonPath.read(body, "$.data[?(@.id == '" + cardId + "')].activity");

        return activities.get(0);

    }

    private Map<String, Object> activityInDetail(UUID cardId) throws Exception {

        return JsonPath.read(bodyOf(mvc.perform(get("/api/cards/{id}", cardId).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())), "$.data.activity");

    }

    private Map<String, Object> repoInDetail(UUID cardId) throws Exception {

        return JsonPath.read(bodyOf(mvc.perform(get("/api/cards/{id}", cardId).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())), "$.data.repo");

    }

    private ResultActions saveSituation(UUID publicId, String situation) throws Exception {

        return mvc.perform(patch("/api/cards/{id}/draft", publicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"situation\":\"" + situation + "\",\"task\":null,\"action\":null,\"result\":null}")
                        .with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk());

    }

    private static Map<String, String> labelsOf(String body) {

        Map<String, String> labels = new HashMap<>();
        List<Map<String, Object>> cards = JsonPath.read(body, "$.data");
        cards.forEach(card -> labels.put((String) card.get("id"), (String) card.get("sourceLabel")));

        return labels;

    }

    private static List<String> idsOf(String body) {

        return JsonPath.read(body, "$.data[*].id");

    }

    private static String bodyOf(ResultActions actions) throws Exception {

        return actions.andReturn().getResponse().getContentAsString();

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
