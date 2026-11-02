package com.gitory.backend.coverletter.api;

import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.agent.port.CoverLetterPostingInput;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.CoverLetterPosting;
import com.gitory.backend.coverletter.domain.CoverLetterRunner;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
class CoverLetterPostingApiTest {

    private static final String HASH = "a".repeat(64);

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
    CoverLetterRunner runner;

    @MockitoBean
    MatchJudgementPort judgementPort;

    @MockitoBean
    CoverLetterAssignmentPort assignment;

    @MockitoBean
    UserRateLimiter rateLimiter;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long card;
    private Long posting;
    private Long spring;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        myUserId = fixtures.insertUser(1L, "grow22");
        card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "Spring 으로 결제 API 를 만들었다", "USER_STATED", null);
        fixtures.confirmCard(card);
        posting = posting();
        criterion("RESPONSIBILITY", 1, "결제 서버 개발과 운영", null);
        spring = criterion("REQUIRED", 2, "Spring 기반 서버 개발 경험", null);
        criterion("REQUIRED", 3, "Kubernetes 운영 경험", null);
        criterion("REQUIRED", 4, "컴퓨터공학 학사 이상", "EDUCATION");

    }

    @Test
    @DisplayName("공고에서 시작하면 그때의 회사·제목·주요 업무와 기술·경험 조건의 판단·채운 카드를 남기고, 일반 조건은 빼며 판단하지 않은 조건은 상태 없이 남긴다")
    void savesThePostingAndItsJudgementsAtCreation() throws Exception {

        judge(Map.of(spring, card));
        criterion("PREFERRED", 5, "AWS 운영 경험", null);

        String created = createFromPosting(publicIdOf(posting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posting.id").value(publicIdOf(posting)))
                .andExpect(jsonPath("$.data.posting.company").value("나래랩스"))
                .andExpect(jsonPath("$.data.posting.title").value("서버 개발자"))
                .andReturn().getResponse().getContentAsString();

        CoverLetterPosting saved = letters.findByPublicIdAndUserId(UUID.fromString(JsonPath.read(created, "$.data.id")),
                myUserId).orElseThrow().getPosting();
        assertThat(saved.responsibilities()).containsExactly("결제 서버 개발과 운영");
        assertThat(saved.conditions()).containsExactly(
                new CoverLetterPosting.Condition("REQUIRED", "Spring 기반 서버 개발 경험", "MET", List.of(card)),
                new CoverLetterPosting.Condition("REQUIRED", "Kubernetes 운영 경험", "NOT_MET", List.of()),
                new CoverLetterPosting.Condition("PREFERRED", "AWS 운영 경험", null, List.of()));

    }

    @Test
    @DisplayName("주요 업무와 조건 글은 AI 자소서 계약의 상한인 500자까지만 남긴다")
    void clipsLongTextsToTheAiLimit() throws Exception {

        criterion("RESPONSIBILITY", 6, "가".repeat(600), null);

        String created = createFromPosting(publicIdOf(posting)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CoverLetterPosting saved = letters.findByPublicIdAndUserId(UUID.fromString(JsonPath.read(created, "$.data.id")),
                myUserId).orElseThrow().getPosting();
        assertThat(saved.responsibilities()).containsExactly("결제 서버 개발과 운영", "가".repeat(500));

    }

    @ParameterizedTest
    @ValueSource(strings = {"9f0c1a52-6b9e-4c63-8f4e-2f2b7c1d9a10", "not-a-uuid"})
    @DisplayName("없는 공고나 UUID 가 아닌 공고 id 면 404 이고, 자소서를 만들지도 호출 제한을 세지도 않는다")
    void unknownPostingIsNotFound(String postingId) throws Exception {

        createFromPosting(postingId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("공고를 찾을 수 없습니다."));

        assertThat(letters.count()).isZero();
        verify(rateLimiter, never()).acquire(RateLimitedAction.COVER_LETTER, myUserId, 1);

    }

    @Test
    @DisplayName("목록과 조회는 공고에서 시작한 자소서에만 그 공고의 id·회사·제목을 담고, 공고 없이 만든 자소서는 null 이다")
    void listShowsPostingOnlyForLettersStartedFromOne() throws Exception {

        judge(Map.of(spring, card));
        createFromPosting(publicIdOf(posting)).andExpect(status().isOk());
        create("""
                {"jobRole": "백엔드 개발", "memo": null, "questions": [{"text": "협업 경험을 쓰세요", "charLimit": 700}]}
                """, "key-plain").andExpect(status().isOk()).andExpect(jsonPath("$.data.posting").value(nullValue()));

        mvc.perform(get("/api/cover-letters").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].posting").value(nullValue()))
                .andExpect(jsonPath("$.data[1].posting.company").value("나래랩스"))
                .andExpect(jsonPath("$.data[1].posting.title").value("서버 개발자"));

    }

    @Test
    @DisplayName("만든 뒤 판단이 바뀌어도 카드 고르기는 만들 때 남긴 공고와 판단을 AI 에 보낸다")
    void aiUsesThePostingSavedAtCreation() throws Exception {

        judge(Map.of(spring, card));
        createFromPosting(publicIdOf(posting)).andExpect(status().isOk());
        jdbc.update("DELETE FROM match_judgement");
        AtomicReference<CoverLetterAssignmentRequest> sent = new AtomicReference<>();
        given(assignment.assign(any(), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return new CoverLetterAssignment(
                    List.of(new CoverLetterAssignment.Question(1, null, null, null, null, List.of(), "FAILED")), List.of());
        });

        runner.run(runner.claimNext().orElseThrow());

        CoverLetterPostingInput posting = sent.get().posting();
        assertThat(posting.company()).isEqualTo("나래랩스");
        assertThat(posting.conditions()).extracting(CoverLetterPostingInput.Condition::status, CoverLetterPostingInput.Condition::metBy)
                .containsExactly(tuple("MET", List.of(card)), tuple("NOT_MET", List.of()));

    }

    // AI 가 조건 id → 그 조건을 채운 카드 id 대로 답하게 하고, 나머지 조건은 아무 카드도 못 채웠다고 답한 뒤 맞춰 본다
    private void judge(Map<Long, Long> metBy) throws Exception {

        when(judgementPort.judge(any())).thenAnswer(call -> ((MatchJudgementRequest) call.getArgument(0)).conditions().stream()
                .map(condition -> new JudgedCondition(condition.conditionId(),
                        metBy.containsKey(Long.valueOf(condition.conditionId()))
                                ? List.of(new ConditionSupport(String.valueOf(metBy.get(Long.valueOf(condition.conditionId()))), "이 카드가 채웠다"))
                                : List.of()))
                .toList());
        mvc.perform(post("/api/matches/{id}/judgement", publicIdOf(posting)).with(me()).with(csrf()))
                .andExpect(status().isOk());

    }

    private ResultActions createFromPosting(String postingId) throws Exception {

        return create("""
                {"jobRole": "백엔드 개발", "memo": null, "questions": [{"text": "지원 동기를 쓰세요", "charLimit": 700}],
                 "postingId": "%s"}
                """.formatted(postingId), "key-" + postingId);

    }

    private ResultActions create(String body, String key) throws Exception {

        return mvc.perform(post("/api/cover-letters").with(me()).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private String publicIdOf(Long id) {

        return jdbc.queryForObject("SELECT public_id::text FROM job_posting WHERE id = ?", String.class, id);

    }

    private Long posting() {

        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_quote, status, content_status, content_hash, indexed_content_hash,
                    first_seen_at, last_seen_at)
                VALUES ('narae', 'posting-1', '나래랩스', '서버 개발자', 'https://careers.example.com/jobs/1', 'BACKEND',
                        'ENTRY', '신입 지원 가능', 'ACTIVE', 'READY', ?, ?, now(), now())
                RETURNING id
                """, Long.class, HASH, HASH);

    }

    private Long criterion(String type, int seq, String text, String generalType) {

        return jdbc.queryForObject("""
                INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote, general_type)
                VALUES (?, ?, ?, ?, '원문 인용', ?)
                RETURNING id
                """, Long.class, posting, type, seq, text, generalType);

    }

    private RequestPostProcessor me() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
