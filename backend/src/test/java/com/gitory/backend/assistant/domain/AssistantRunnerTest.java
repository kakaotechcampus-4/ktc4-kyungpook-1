package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.AssistantStepPort;
import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.agent.port.AssistantStepRequest;
import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.assistant.infra.AssistantRunRepository;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.assistant.worker.enabled=false"
})
@Testcontainers
class AssistantRunnerTest {

    private static final int STEP_LIMIT = 6;
    private static final UUID REPO = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    AssistantRunner runner;

    @Autowired
    AssistantRunRepository runs;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AssistantStepPort stepPort;

    @MockitoBean
    AssistantTools tools;

    private TestFixtures fixtures;
    private Long userId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("도구를 고르면 걸음을 하는 중으로 먼저 저장하고, 실행한 뒤 결과로 바꾼다")
    void savesStepAsRunningBeforeExecutingAndReplacesItWithResult() {

        AssistantRun run = newRun("토스 공고 찾아 줘");
        UUID postingId = UUID.randomUUID();
        AtomicReference<AssistantRun> during = new AtomicReference<>();
        given(stepPort.next(any())).willReturn(findCall("토스"), AssistantStepReply.finish("한 건을 찾았어요."));
        given(tools.execute(any(), anyInt(), any())).willAnswer(invocation -> {
            during.set(reload(run));
            return ToolResult.found(List.of(posting(postingId)), 1);
        });

        runOnce();

        RunItem midway = during.get().getItems().get(1);
        assertThat(during.get().getStepsUsed()).isEqualTo(1);
        assertThat(midway.kind()).isEqualTo(RunItem.Kind.STEP);
        assertThat(midway.tool()).isEqualTo(AssistantTool.FIND_POSTINGS);
        assertThat(midway.ok()).isNull();
        assertThat(midway.find()).isEqualTo(new RunItem.Find("토스", List.of()));
        assertThat(midway.postings()).isNull();

        AssistantRun finished = reload(run);
        RunItem step = finished.getItems().get(1);
        assertThat(finished.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(finished.getStepsUsed()).isEqualTo(1);
        assertThat(step.seq()).isEqualTo(2);
        assertThat(step.ok()).isTrue();
        assertThat(step.totalFound()).isEqualTo(1);
        assertThat(step.postings()).extracting(RunItem.Posting::ref, RunItem.Posting::postingId)
                .containsExactly(tuple("p1", postingId));

    }

    @Test
    @DisplayName("물으면 물음을 남기고 사용자를 기다린다")
    void asksAndWaitsForUser() {

        AssistantRun run = newRun("자소서 써 줘");
        given(stepPort.next(any()))
                .willReturn(AssistantStepReply.ask("지원서의 문항을 붙여 넣어 주세요.", "QUESTIONS", List.of()));

        runOnce();

        AssistantRun waiting = reload(run);
        assertThat(waiting.getState()).isEqualTo(AssistantRunState.WAITING_USER);
        assertThat(waiting.getRunningSince()).isNull();
        assertThat(waiting.getItems().get(1))
                .extracting(RunItem::seq, RunItem::kind, RunItem::text, RunItem::askKind, RunItem::options)
                .containsExactly(2, RunItem.Kind.ASK, "지원서의 문항을 붙여 넣어 주세요.", "QUESTIONS", List.of());
        assertThat(runner.claimNext()).isEmpty();
        verifyNoInteractions(tools);

    }

    @Test
    @DisplayName("끝내면 끝맺음 말을 남기고 닫는다")
    void finishesWithClosingSay() {

        AssistantRun run = newRun("공고 알려 줘");
        given(stepPort.next(any())).willReturn(AssistantStepReply.finish("지금은 맞는 공고가 없어요."));

        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getRunningSince()).isNull();
        assertThat(done.getItems().get(1)).extracting(RunItem::seq, RunItem::kind, RunItem::text)
                .containsExactly(2, RunItem.Kind.SAY, "지금은 맞는 공고가 없어요.");

    }

    @Test
    @DisplayName("걸음 상한에 닿은 뒤에도 도구를 고르면 실행하지 않고 닫는다")
    void closesWithoutExecutingToolAfterStepLimit() {

        AssistantRun run = newRun("공고 찾아 줘", 1, List.of());
        given(stepPort.next(any())).willReturn(findCall("토스"), findCall("카카오"));
        given(tools.execute(any(), anyInt(), any()))
                .willReturn(ToolResult.found(List.of(posting(UUID.randomUUID())), 1));

        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getStepsUsed()).isEqualTo(1);
        assertThat(done.getItems()).extracting(RunItem::kind).containsExactly(RunItem.Kind.USER, RunItem.Kind.STEP);
        verify(tools, times(1)).execute(any(), anyInt(), any());
        ArgumentCaptor<AssistantStepRequest> sent = ArgumentCaptor.forClass(AssistantStepRequest.class);
        verify(stepPort, times(2)).next(sent.capture());
        assertThat(sent.getAllValues()).extracting(AssistantStepRequest::stepsLeft).containsExactly(1, 0);

    }

    @Test
    @DisplayName("도구가 실패해도 실행은 이어지고, 실패 코드를 걸음에 남겨 AI 에 되돌려 보낸다")
    void continuesAfterToolFailureAndReturnsCodeToAi() {

        AssistantRun run = newRun("공고 찾아 줘");
        given(stepPort.next(any())).willReturn(findCall("토스"), AssistantStepReply.finish("잠시 뒤에 다시 해 주세요."));
        given(tools.execute(any(), anyInt(), any())).willReturn(ToolResult.failed(ToolError.RATE_LIMITED));

        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getItems().get(1)).extracting(RunItem::ok, RunItem::error).containsExactly(false, "RATE_LIMITED");
        ArgumentCaptor<AssistantStepRequest> sent = ArgumentCaptor.forClass(AssistantStepRequest.class);
        verify(stepPort, times(2)).next(sent.capture());
        assertThat(sent.getAllValues().get(1).steps())
                .extracting(AssistantStepRequest.DoneStep::seq, AssistantStepRequest.DoneStep::tool,
                        AssistantStepRequest.DoneStep::ok, AssistantStepRequest.DoneStep::error)
                .containsExactly(tuple(2, AssistantTool.FIND_POSTINGS, false, "RATE_LIMITED"));

    }

    @Test
    @DisplayName("이 실행에 없는 이름표를 고르면 도구를 실행하지 않고 찾을 수 없음으로 남긴다")
    void unknownRefIsNotFoundWithoutExecutingTool() {

        AssistantRun run = newRun("자소서 써 줘");
        given(stepPort.next(any())).willReturn(
                AssistantStepReply.call(AssistantTool.MATCH_POSTING, null, "p9", null, null, null, null, List.of()),
                AssistantStepReply.call(AssistantTool.START_REPO_ANALYSIS, null, null, "r9", null, null, null, List.of()),
                AssistantStepReply.call(AssistantTool.ASK_CARD_GAP, null, null, null, "c9", null, null, List.of()),
                AssistantStepReply.call(AssistantTool.POLISH_COVER_LETTER, null, null, null, null, "l9", null,
                        List.of()),
                AssistantStepReply.finish("찾을 수 없어요."));

        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getStepsUsed()).isEqualTo(4);
        assertThat(done.getItems()).extracting(RunItem::kind, RunItem::tool, RunItem::ok, RunItem::error)
                .containsExactly(
                        tuple(RunItem.Kind.USER, null, null, null),
                        tuple(RunItem.Kind.STEP, AssistantTool.MATCH_POSTING, false, "NOT_FOUND"),
                        tuple(RunItem.Kind.STEP, AssistantTool.START_REPO_ANALYSIS, false, "NOT_FOUND"),
                        tuple(RunItem.Kind.STEP, AssistantTool.ASK_CARD_GAP, false, "NOT_FOUND"),
                        tuple(RunItem.Kind.STEP, AssistantTool.POLISH_COVER_LETTER, false, "NOT_FOUND"),
                        tuple(RunItem.Kind.SAY, null, null, null));
        verifyNoInteractions(tools);

    }

    @Test
    @DisplayName("공고 이름표는 처음 나온 순서로 붙이고, 같은 공고가 다시 나오면 같은 이름표를 쓴다")
    void labelsPostingsInOrderOfFirstAppearance() {

        AssistantRun run = newRun("공고 찾아 줘");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        given(stepPort.next(any())).willReturn(findCall("토스"), findCall("카카오"), AssistantStepReply.finish("끝"));
        given(tools.execute(any(), anyInt(), any())).willReturn(
                ToolResult.found(List.of(posting(first), posting(second)), 2),
                ToolResult.found(List.of(posting(second), posting(third)), 2));

        runOnce();

        List<RunItem> items = reload(run).getItems();
        assertThat(items.get(1).postings()).extracting(RunItem.Posting::ref, RunItem.Posting::postingId)
                .containsExactly(tuple("p1", first), tuple("p2", second));
        assertThat(items.get(2).postings()).extracting(RunItem.Posting::ref, RunItem.Posting::postingId)
                .containsExactly(tuple("p2", second), tuple("p3", third));

    }

    @Test
    @DisplayName("이름표로 고른 공고를 그 공고로 풀어 도구에 넘기고, 맞춰 본 결과를 같은 줄에 채운다")
    void resolvesPostingRefBeforeCallingTool() {

        AssistantRun run = newRun("토스 공고 맞춰 봐 줘");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(stepPort.next(any())).willReturn(findCall("토스"),
                AssistantStepReply.call(AssistantTool.MATCH_POSTING, null, "p2", null, null, null, null, List.of()),
                AssistantStepReply.finish("필수 3개 중 2개가 맞아요."));
        given(tools.execute(any(), anyInt(), any())).willAnswer(invocation -> {
            ToolCall call = invocation.getArgument(2);
            if (call.tool() == AssistantTool.FIND_POSTINGS) {
                return ToolResult.found(List.of(posting(first), posting(second)), 2);
            }
            return ToolResult.matched(call.posting().withRequired(2, 3),
                    new RunItem.Match(2, 3, List.of("자바 경험"), List.of("도커 배포 경험")));
        });

        runOnce();

        ArgumentCaptor<ToolCall> calls = ArgumentCaptor.forClass(ToolCall.class);
        verify(tools, times(2)).execute(any(), anyInt(), calls.capture());
        assertThat(calls.getAllValues().get(1).posting().postingId()).isEqualTo(second);
        RunItem matched = reload(run).getItems().get(2);
        assertThat(matched).extracting(RunItem::seq, RunItem::tool, RunItem::ok, RunItem::postingRef)
                .containsExactly(3, AssistantTool.MATCH_POSTING, true, "p2");
        assertThat(matched.posting().postingId()).isEqualTo(second);
        assertThat(matched.posting().requiredMet()).isEqualTo(2);
        assertThat(matched.match().missing()).containsExactly("도커 배포 경험");

    }

    @ParameterizedTest
    @MethodSource("aiFailures")
    @DisplayName("자소서나 정리를 시작한 걸음이 없을 때 AI 를 부르지 못하면 실패로 닫는다")
    void failsWhenAiCannotBeCalled(RuntimeException thrown, AssistantRunError expected) {

        AssistantRun run = newRun("공고 찾아 줘");
        given(stepPort.next(any())).willThrow(thrown);

        runOnce();

        AssistantRun failed = reload(run);
        assertThat(failed.getState()).isEqualTo(AssistantRunState.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(expected.name());
        assertThat(failed.getRunningSince()).isNull();

    }

    static Stream<Arguments> aiFailures() {

        return Stream.of(
                Arguments.of(new AgentUnavailableException("AI 실패", true, false, null),
                        AssistantRunError.AI_UNAVAILABLE),
                Arguments.of(new AgentUnavailableException("이상 응답", false, true, null),
                        AssistantRunError.AI_UNAVAILABLE),
                Arguments.of(new IllegalStateException("예상 못 한 실패"), AssistantRunError.INTERNAL_ERROR));

    }

    @ParameterizedTest
    @MethodSource("startedWork")
    @DisplayName("자소서나 정리를 시작한 뒤에 AI 를 부르지 못하면 실패가 아니라 끝맺음 말 없이 닫는다")
    void closesWithoutSayWhenAiFailsAfterStartingWork(AssistantStepReply start, ToolResult started) {

        AssistantRun run = newRun("자소서 써 줘", STEP_LIMIT, List.of(new RepoLabel("r1", REPO, "gitory")));
        given(stepPort.next(any())).willReturn(start)
                .willThrow(new AgentUnavailableException("AI 실패", true, false, null));
        given(tools.execute(any(), anyInt(), any())).willReturn(started);

        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getErrorCode()).isNull();
        assertThat(done.getRunningSince()).isNull();
        assertThat(done.getItems()).extracting(RunItem::kind, RunItem::tool, RunItem::ok)
                .containsExactly(tuple(RunItem.Kind.USER, null, null), tuple(RunItem.Kind.STEP, start.tool(), true));

    }

    static Stream<Arguments> startedWork() {

        return Stream.of(
                Arguments.of(AssistantStepReply.call(AssistantTool.START_COVER_LETTER, null, null, null, null, null, "백엔드 개발자",
                        List.of(new AssistantStepReply.Question("지원 동기를 쓰세요", null))),
                        ToolResult.coverLetter(1, UUID.randomUUID())),
                Arguments.of(AssistantStepReply.call(AssistantTool.START_REPO_ANALYSIS, null, null, "r1", null, null,
                        null, List.of()), ToolResult.repoAnalysis(REPO, UUID.randomUUID())));

    }

    @ParameterizedTest
    @MethodSource("workThatStartedNothing")
    @DisplayName("공고만 찾았거나 시작이 실패한 뒤에 AI 를 부르지 못하면 그대로 실패로 닫는다")
    void failsWhenAiFailsAfterStepsThatStartedNothing(AssistantStepReply call, ToolResult result) {

        AssistantRun run = newRun("자소서 써 줘");
        given(stepPort.next(any())).willReturn(call)
                .willThrow(new AgentUnavailableException("AI 실패", true, false, null));
        given(tools.execute(any(), anyInt(), any())).willReturn(result);

        runOnce();

        AssistantRun failed = reload(run);
        assertThat(failed.getState()).isEqualTo(AssistantRunState.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("AI_UNAVAILABLE");
        assertThat(failed.getItems()).hasSize(2);

    }

    static Stream<Arguments> workThatStartedNothing() {

        return Stream.of(
                Arguments.of(findCall("토스"), ToolResult.found(List.of(posting(UUID.randomUUID())), 1)),
                Arguments.of(AssistantStepReply.call(AssistantTool.START_COVER_LETTER, null, null, null, null, null, "백엔드 개발자",
                        List.of(new AssistantStepReply.Question("지원 동기를 쓰세요", null))),
                        ToolResult.failed(ToolError.NO_CONFIRMED_CARDS)));

    }

    @Test
    @DisplayName("AI 에는 내 말 전부와 끝난 걸음, 확정 카드 수 · 레포 · 관심 분야, 남은 걸음 수를 보낸다")
    void sendsEverythingAiNeedsToChooseNextStep() {

        fixtures.confirmCard(fixtures.insertCard(UUID.randomUUID(), userId));
        fixtures.confirmCard(fixtures.insertCard(UUID.randomUUID(), userId));
        UUID analyzedRepo = UUID.randomUUID();
        UUID analyzingRepo = UUID.randomUUID();
        Long analyzed = fixtures.insertUserRepository(analyzedRepo, userId,
                fixtures.insertRepository(1L, "grow22", "gitory"));
        Long analyzing = fixtures.insertUserRepository(analyzingRepo, userId,
                fixtures.insertRepository(2L, "grow22", "gitory-docs"));
        jdbc.update("UPDATE user_repository SET last_analyzed_at = now() WHERE id = ?", analyzed);
        jdbc.update("INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) "
                + "VALUES (?, ?, 'key', 'ANALYZE', 'RUNNING')", userId, analyzing);
        jdbc.update("INSERT INTO user_profile (user_id, saved_at, interests) "
                + "VALUES (?, now(), ARRAY['BACKEND', 'AI_ML', 'ETC'])", userId);
        AssistantRun run = newRun("자소서 써 줘", STEP_LIMIT, List.of(new RepoLabel("r1", analyzedRepo, "gitory"),
                new RepoLabel("r2", analyzingRepo, "gitory-docs")),
                List.of(new CardLabel("c1", UUID.randomUUID(), "결제 서버 개선", List.of(StarField.S, StarField.R))),
                List.of(new LetterLabel("l1", UUID.randomUUID(), "백엔드 개발자", "토스", 3)));
        run.ask("문항을 붙여 넣어 주세요.", "QUESTIONS", List.of());
        run.addUserMessage("지원 동기를 쓰세요");
        runs.save(run);
        given(stepPort.next(any())).willReturn(findCall("토스"), AssistantStepReply.finish("끝"));
        given(tools.execute(any(), anyInt(), any()))
                .willReturn(ToolResult.found(List.of(posting(UUID.randomUUID())), 1));

        runOnce();

        ArgumentCaptor<AssistantStepRequest> sent = ArgumentCaptor.forClass(AssistantStepRequest.class);
        verify(stepPort, times(2)).next(sent.capture());
        AssistantStepRequest first = sent.getAllValues().get(0);
        AssistantStepRequest second = sent.getAllValues().get(1);
        assertThat(first.messages()).extracting(AssistantStepRequest.Message::seq, AssistantStepRequest.Message::text)
                .containsExactly(tuple(1, "자소서 써 줘"), tuple(3, "지원 동기를 쓰세요"));
        assertThat(first.steps()).isEmpty();
        assertThat(first.stepsLeft()).isEqualTo(6);
        assertThat(first.context().confirmedCards()).isEqualTo(2);
        assertThat(first.context().repos())
                .extracting(AssistantStepRequest.Repo::ref, AssistantStepRequest.Repo::name,
                        AssistantStepRequest.Repo::analyzed, AssistantStepRequest.Repo::analyzing)
                .containsExactly(tuple("r1", "gitory", true, false), tuple("r2", "gitory-docs", false, true));
        assertThat(first.context().interests()).containsExactly("백엔드", "AI");
        assertThat(first.context().cards())
                .extracting(AssistantStepRequest.Card::ref, AssistantStepRequest.Card::title,
                        AssistantStepRequest.Card::missing)
                .containsExactly(tuple("c1", "결제 서버 개선", List.of("상황", "결과")));
        assertThat(first.context().letters())
                .extracting(AssistantStepRequest.Letter::ref, AssistantStepRequest.Letter::jobRole,
                        AssistantStepRequest.Letter::company, AssistantStepRequest.Letter::questionCount)
                .containsExactly(tuple("l1", "백엔드 개발자", "토스", 3));
        assertThat(second.stepsLeft()).isEqualTo(5);
        assertThat(second.steps())
                .extracting(AssistantStepRequest.DoneStep::seq, AssistantStepRequest.DoneStep::tool,
                        AssistantStepRequest.DoneStep::ok, AssistantStepRequest.DoneStep::totalFound)
                .containsExactly(tuple(4, AssistantTool.FIND_POSTINGS, true, 1));
        assertThat(second.steps().get(0).postings()).extracting(AssistantStepRequest.Posting::ref)
                .containsExactly("p1");

    }

    @Test
    @DisplayName("채우기·카드 질문·첨삭 걸음은 이름표와 결과를 줄에 남기고 다음 AI 요청에 되돌려 보낸다")
    void keepsFillAskAndPolishResultsInStepsSentBackToAi() {

        UUID cardId = UUID.randomUUID();
        UUID letterId = UUID.randomUUID();
        AssistantRun run = newRun("채우고 질문하고 첨삭해 줘", STEP_LIMIT, List.of(),
                List.of(new CardLabel("c1", cardId, "결제 서버 개선", List.of(StarField.R))),
                List.of(new LetterLabel("l1", letterId, "백엔드 개발자", "토스", 3)));
        given(stepPort.next(any())).willReturn(findCall("토스"),
                AssistantStepReply.call(AssistantTool.FILL_POSTING_GAPS, null, "p1", null, null, null, null, List.of()),
                AssistantStepReply.call(AssistantTool.ASK_CARD_GAP, null, null, null, "c1", null, null, List.of()),
                AssistantStepReply.call(AssistantTool.POLISH_COVER_LETTER, null, null, null, null, "l1", null,
                        List.of()),
                AssistantStepReply.finish("끝"));
        given(tools.execute(any(), anyInt(), any())).willAnswer(invocation -> {
            ToolCall call = invocation.getArgument(2);
            return switch (call.tool()) {
                case FIND_POSTINGS -> ToolResult.found(List.of(posting(UUID.randomUUID())), 1);
                case FILL_POSTING_GAPS -> ToolResult.gapsFilled(call.posting().withRequired(3, 3),
                        new RunItem.Match(3, 3, List.of("자바 경험"), List.of()), 2);
                case ASK_CARD_GAP -> ToolResult.asked(call.card().cardId(), call.card().title(), "R",
                        "결과는 어땠나요?");
                case POLISH_COVER_LETTER -> ToolResult.polished(call.letter().letterId(),
                        List.of(new RunItem.Polished(1, 3)), 2);
                default -> throw new IllegalStateException();
            };
        });

        runOnce();

        List<RunItem> items = reload(run).getItems();
        assertThat(items.get(2)).extracting(RunItem::postingRef, RunItem::gapsFilled)
                .containsExactly("p1", 2);
        assertThat(items.get(2).posting().requiredMet()).isEqualTo(3);
        assertThat(items.get(3)).extracting(RunItem::cardRef, RunItem::cardId, RunItem::cardTitle, RunItem::question)
                .containsExactly("c1", cardId, "결제 서버 개선", new RunItem.Question("R", "결과는 어땠나요?"));
        assertThat(items.get(4)).extracting(RunItem::letterRef, RunItem::letterId, RunItem::polished,
                        RunItem::remainingQuestions)
                .containsExactly("l1", letterId, List.of(new RunItem.Polished(1, 3)), 2);
        ArgumentCaptor<AssistantStepRequest> sent = ArgumentCaptor.forClass(AssistantStepRequest.class);
        verify(stepPort, times(5)).next(sent.capture());
        List<AssistantStepRequest.DoneStep> steps = sent.getAllValues().get(4).steps();
        assertThat(steps.get(1)).extracting(AssistantStepRequest.DoneStep::postingRef,
                        AssistantStepRequest.DoneStep::gapsFilled)
                .containsExactly("p1", 2);
        assertThat(steps.get(1).match().requiredMet()).isEqualTo(3);
        assertThat(steps.get(2)).extracting(AssistantStepRequest.DoneStep::cardRef,
                        AssistantStepRequest.DoneStep::question)
                .containsExactly("c1", new AssistantStepRequest.QuestionAsked("R", "결과는 어땠나요?"));
        assertThat(steps.get(3)).extracting(AssistantStepRequest.DoneStep::letterRef,
                        AssistantStepRequest.DoneStep::polished, AssistantStepRequest.DoneStep::remainingQuestions)
                .containsExactly("l1", List.of(new AssistantStepRequest.Polished(1, 3)), 2);

    }

    @Test
    @DisplayName("집은 실행은 다른 워커가 집지 않고, 5분 동안 진행이 없으면 실패로 닫는다")
    void claimedRunIsNotClaimedAgainAndStuckRunFails() {

        AssistantRun stuck = newRun("공고 찾아 줘");
        AssistantRun alive = newRun("자소서 써 줘");

        assertThat(runner.claimNext()).get().extracting(AssistantRun::getId).isEqualTo(stuck.getId());
        assertThat(runner.claimNext()).get().extracting(AssistantRun::getId).isEqualTo(alive.getId());
        assertThat(runner.claimNext()).isEmpty();
        jdbc.update("UPDATE assistant_run SET updated_at = now() - interval '6 minutes' WHERE id = ?", stuck.getId());
        jdbc.update("UPDATE assistant_run SET updated_at = now() - interval '4 minutes' WHERE id = ?", alive.getId());

        runner.failStuck();

        AssistantRun failed = reload(stuck);
        assertThat(failed.getState()).isEqualTo(AssistantRunState.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.getRunningSince()).isNull();
        assertThat(reload(alive).getState()).isEqualTo(AssistantRunState.RUNNING);
        assertThat(reload(alive).getRunningSince()).isNotNull();

    }

    @Test
    @DisplayName("서버가 다시 뜨면 돌던 실행을 다시 집을 수 있고, 하다 만 걸음은 실패로 닫고 이어 간다")
    void requeuesInterruptedRunAndClosesHalfDoneStep() {

        AssistantRun run = newRun("공고 찾아 줘");
        run.claim();
        run.startStep(ToolCall.of(run, findCall("토스")));
        runs.save(run);
        given(stepPort.next(any())).willReturn(AssistantStepReply.finish("다시 해 볼게요."));

        assertThat(runner.claimNext()).isEmpty();
        runner.requeueRunning();
        assertThat(reload(run).getRunningSince()).isNull();
        runOnce();

        AssistantRun done = reload(run);
        assertThat(done.getState()).isEqualTo(AssistantRunState.DONE);
        assertThat(done.getItems()).extracting(RunItem::kind, RunItem::ok, RunItem::error)
                .containsExactly(tuple(RunItem.Kind.USER, null, null), tuple(RunItem.Kind.STEP, false, "FAILED"),
                        tuple(RunItem.Kind.SAY, null, null));
        ArgumentCaptor<AssistantStepRequest> sent = ArgumentCaptor.forClass(AssistantStepRequest.class);
        verify(stepPort).next(sent.capture());
        assertThat(sent.getValue().steps())
                .extracting(AssistantStepRequest.DoneStep::seq, AssistantStepRequest.DoneStep::ok,
                        AssistantStepRequest.DoneStep::error)
                .containsExactly(tuple(2, false, "FAILED"));

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 실패로 닫힌 실행에는 늦게 온 결과를 저장하지 않는다")
    void dropsResultThatArrivesAfterRunWasFailed() {

        AssistantRun run = newRun("공고 찾아 줘");
        given(stepPort.next(any())).willAnswer(invocation -> {
            jdbc.update("UPDATE assistant_run SET state = 'FAILED', error_code = 'INTERNAL_ERROR', "
                    + "running_since = NULL WHERE id = ?", run.getId());
            return AssistantStepReply.finish("끝났어요.");
        });

        runOnce();

        AssistantRun failed = reload(run);
        assertThat(failed.getState()).isEqualTo(AssistantRunState.FAILED);
        assertThat(failed.getItems()).hasSize(1);
        verifyNoInteractions(tools);

    }

    private AssistantRun newRun(String text) {

        return newRun(text, STEP_LIMIT, List.of());

    }

    private AssistantRun newRun(String text, int stepLimit, List<RepoLabel> repos) {

        return newRun(text, stepLimit, repos, List.of(), List.of());

    }

    private AssistantRun newRun(String text, int stepLimit, List<RepoLabel> repos, List<CardLabel> cards,
                                List<LetterLabel> letters) {

        return runs.save(AssistantRun.start(userId, text, stepLimit, repos, cards, letters));

    }

    private AssistantRun reload(AssistantRun run) {

        return runs.findById(run.getId()).orElseThrow();

    }

    private void runOnce() {

        runner.run(runner.claimNext().orElseThrow());

    }

    private static AssistantStepReply findCall(String company) {

        return AssistantStepReply.call(AssistantTool.FIND_POSTINGS, new AssistantStepReply.Find(company, List.of()),
                null, null, null, null, null, List.of());

    }

    private static RunItem.Posting posting(UUID postingId) {

        return new RunItem.Posting(null, postingId, "토스", "백엔드 개발자", 3, null, null);

    }
}
