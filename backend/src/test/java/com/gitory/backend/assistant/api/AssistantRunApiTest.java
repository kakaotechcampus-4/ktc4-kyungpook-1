package com.gitory.backend.assistant.api;

import com.gitory.backend.assistant.domain.AssistantRun;
import com.gitory.backend.assistant.infra.AssistantRunRepository;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.assistant.enabled=true",
        "gitory.assistant.worker.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class AssistantRunApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AssistantRunRepository runs;

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

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("말은 앞뒤 공백을 뺀 1자부터 500자까지이고, 비었거나 넘거나 U+0000 이 있으면 400 이고 실행을 만들지 않는다")
    void rejectsInvalidText(String body) throws Exception {

        start(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(runCount()).isZero();
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    static Stream<String> invalidBodies() {

        return Stream.of(json(""), json("   "), json("\n\t"), json("가".repeat(501)), json(" " + "가".repeat(501) + " "),
                json("가\u0000나"), "{}", "{\"text\": null}");

    }

    @Test
    @DisplayName("실행을 만들면 앞뒤 공백을 뺀 내 말이 첫 줄이고 RUNNING 이며, 호출 제한을 한 번 센다")
    void createsRunningRunWithTrimmedFirstLine() throws Exception {

        String text = "가".repeat(500);

        start(json("  " + text + "\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.stepsUsed").value(0))
                .andExpect(jsonPath("$.data.stepLimit").value(6))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000))
                .andExpect(jsonPath("$.data.errorCode").value(nullValue()))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].seq").value(1))
                .andExpect(jsonPath("$.data.items[0].kind").value("USER"))
                .andExpect(jsonPath("$.data.items[0].text").value(text));

        assertThat(runCount()).isEqualTo(1);
        verify(rateLimiter).acquire(RateLimitedAction.ASSISTANT, myUserId, 1);

    }

    @Test
    @DisplayName("남의 실행이나 없는 실행은 404 이고 거절 기록을 남긴다")
    void othersOrMissingRunIsNotFoundAndAudited() throws Exception {

        AssistantRun others = waitingRun(othersUserId, 1);
        AssistantRun mine = waitingRun(myUserId, 1);

        read(others.getPublicId()).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        say(others.getPublicId(), json("답")).andExpect(status().isNotFound());
        read(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get("/api/assistant/runs/{id}", "not-a-uuid").with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound());

        assertThat(audit("VIEW_ASSISTANT_RUN")).isEqualTo(1);
        assertThat(audit("EDIT_ASSISTANT_RUN")).isEqualTo(1);
        assertThat(runs.findById(others.getId()).orElseThrow().getItems()).hasSize(2);
        read(mine.getPublicId()).andExpect(status().isOk());

    }

    @Test
    @DisplayName("물음을 기다리는 실행에만 말을 더할 수 있고, 더하면 다시 RUNNING 이다")
    void addsMessageOnlyWhileWaitingForUser() throws Exception {

        AssistantRun waiting = waitingRun(myUserId, 1);
        AssistantRun running = runs.save(AssistantRun.start(myUserId, "공고 찾아 줘", 6, List.of()));
        AssistantRun done = runs.save(finished(AssistantRun.start(myUserId, "공고 찾아 줘", 6, List.of())));

        say(waiting.getPublicId(), json("  지원 동기를 적어 주세요  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[1].kind").value("ASK"))
                .andExpect(jsonPath("$.data.items[1].askKind").value("FREE"))
                .andExpect(jsonPath("$.data.items[2].seq").value(3))
                .andExpect(jsonPath("$.data.items[2].kind").value("USER"))
                .andExpect(jsonPath("$.data.items[2].text").value("지원 동기를 적어 주세요"));
        say(running.getPublicId(), json("답")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        say(done.getPublicId(), json("답")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(runs.findById(waiting.getId()).orElseThrow().getItems()).hasSize(3);
        assertThat(runs.findById(running.getId()).orElseThrow().getItems()).hasSize(1);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.ASSISTANT, myUserId, 1);

    }

    @Test
    @DisplayName("한 실행에 내 말은 10개까지다")
    void allowsAtMostTenUserMessages() throws Exception {

        AssistantRun ninth = waitingRun(myUserId, 9);
        AssistantRun tenth = waitingRun(myUserId, 10);

        say(ninth.getPublicId(), json("열 번째 말")).andExpect(status().isOk());
        say(tenth.getPublicId(), json("열한 번째 말")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(runs.findById(ninth.getId()).orElseThrow().userMessageCount()).isEqualTo(10);
        assertThat(runs.findById(tenth.getId()).orElseThrow().userMessageCount()).isEqualTo(10);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.ASSISTANT, myUserId, 1);

    }

    @Test
    @DisplayName("응답은 이름표를 싣지 않고 id 를 문자열로 주며, 도는 동안에만 다시 읽을 간격을 준다")
    void responseHidesRefsAndPollsOnlyWhileRunning() throws Exception {

        UUID postingId = UUID.randomUUID();
        UUID coverLetterId = UUID.randomUUID();
        AssistantRun running = runs.save(AssistantRun.start(myUserId, "토스 공고에 맞춰 자소서 써 줘", 6, List.of()));
        jdbc.update("UPDATE assistant_run SET items = CAST(? AS jsonb), steps_used = 3 WHERE id = ?", """
                [{"seq":1,"kind":"USER","text":"토스 공고에 맞춰 자소서 써 줘"},
                 {"seq":2,"kind":"STEP","tool":"FIND_POSTINGS","ok":true,"find":{"company":"토스","keywords":[]},
                  "postings":[{"ref":"p1","postingId":"%s","company":"토스","title":"백엔드","daysLeft":2,
                               "requiredMet":null,"requiredTotal":null}],"totalFound":1},
                 {"seq":3,"kind":"STEP","tool":"MATCH_POSTING","postingRef":"p1",
                  "posting":{"ref":"p1","postingId":"%s","company":"토스","title":"백엔드","daysLeft":2,
                             "requiredMet":null,"requiredTotal":null}},
                 {"seq":4,"kind":"STEP","tool":"START_COVER_LETTER","postingRef":"p1","ok":true,"questionCount":2,
                  "coverLetterId":"%s"}]
                """.formatted(postingId, postingId, coverLetterId), running.getId());
        AssistantRun done = runs.save(finished(AssistantRun.start(myUserId, "공고 찾아 줘", 6, List.of())));

        read(running.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(running.getPublicId().toString()))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000))
                .andExpect(jsonPath("$.data.items[1].postings[0].postingId").value(postingId.toString()))
                .andExpect(jsonPath("$.data.items[1].postings[0].daysLeft").value(2))
                .andExpect(jsonPath("$.data.items[1].postings[0].ref").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].find.company").value("토스"))
                .andExpect(jsonPath("$.data.items[2].postingRef").doesNotExist())
                .andExpect(jsonPath("$.data.items[2].posting.postingId").value(postingId.toString()))
                .andExpect(jsonPath("$.data.items[2].posting.ref").doesNotExist())
                .andExpect(jsonPath("$.data.items[2].ok").doesNotExist())
                .andExpect(jsonPath("$.data.items[3].coverLetterId").value(coverLetterId.toString()))
                .andExpect(jsonPath("$.data.items[3].postingRef").doesNotExist());
        read(done.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DONE"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(nullValue()));

    }

    private AssistantRun waitingRun(Long userId, int userMessages) {

        AssistantRun run = AssistantRun.start(userId, "첫 말", 6, List.of());
        for (int count = 1; count < userMessages; count++) {
            run.ask("더 알려 주세요", "FREE", List.of());
            run.addUserMessage("답 " + count);
        }
        run.ask("더 알려 주세요", "FREE", List.of());

        return runs.save(run);

    }

    private static AssistantRun finished(AssistantRun run) {

        run.finish("찾았어요");

        return run;

    }

    private int runCount() {

        return jdbc.queryForObject("SELECT count(*) FROM assistant_run", Integer.class);

    }

    private int audit(String action) {

        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND outcome = 'DENIED'",
                Integer.class, action);

    }

    private static String json(String text) {

        return JSON.writeValueAsString(Map.of("text", text));

    }

    private ResultActions start(String body) throws Exception {

        return mvc.perform(post("/api/assistant/runs").with(loggedInAs(myUserId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions say(UUID runId, String body) throws Exception {

        return mvc.perform(post("/api/assistant/runs/{id}/messages", runId).with(loggedInAs(myUserId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));

    }

    private ResultActions read(UUID runId) throws Exception {

        return mvc.perform(get("/api/assistant/runs/{id}", runId).with(loggedInAs(myUserId)));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
