package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.AssistantStepPort;
import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.assistant.infra.AssistantRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 집은 실행을 AI 가 끝내거나 사용자에게 물을 때까지 다음 행동 고르기와 도구 실행을 되풀이한다
 * AI 와 도구는 트랜잭션 밖에서 부르고, 걸음마다 집은 표를 확인한 뒤 저장해 응답을 기다리는 동안 DB 커넥션과 잠금을 붙잡지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantRunner {

    // AI 응답(30초)과 공고 맞춰 보기(1분 남짓)보다 넉넉하고, 걸음마다 updatedAt 이 바뀌어 살아 있는 실행은 멈춘 것으로 보지 않는다
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final AssistantRunRepository runs;
    private final AssistantStepRequestFactory requests;
    private final AssistantStepPort stepPort;
    private final AssistantTools tools;
    private final TransactionTemplate transaction;

    /** 집은 실행은 집은 시각을 커밋한 뒤에 돌려주고, 기다리는 실행이 없으면 빈 값을 돌려준다 */
    public Optional<AssistantRun> claimNext() {

        return transaction.execute(status -> runs.findNextWaitingForUpdate().map(waiting -> {
            waiting.claim();
            return waiting;
        }));

    }

    /** 끝나거나 사용자의 답을 기다리게 될 때까지 돌리며, AI 를 끝내 부르지 못하거나 예상 못 한 실패가 나면 실행을 닫는다 */
    public void run(AssistantRun claimed) {

        try {
            save(claimed, AssistantRun::closeInterruptedStep);
            boolean continues = true;
            while (continues) {
                continues = advance(claimed);
            }
        } catch (AgentUnavailableException failure) {
            log.warn("홈 한 줄 명령 실행 {} 의 AI 호출이 끝내 실패해 닫는다", claimed.getId(), failure);
            save(claimed, AssistantRunner::closeAfterAiFailure);
        } catch (RuntimeException failure) {
            log.warn("홈 한 줄 명령 실행 {} 을 {} 로 끝낸다", claimed.getId(), AssistantRunError.INTERNAL_ERROR, failure);
            save(claimed, locked -> locked.fail(AssistantRunError.INTERNAL_ERROR));
        }

    }

    /** 집은 지 5분이 지나도록 걸음을 저장하지 않은 실행은 실패로 끝낸다 */
    public void failStuck() {

        Instant before = Instant.now().minus(STUCK_AFTER);
        transaction.executeWithoutResult(status -> runs.findStuckForUpdate(before).forEach(stuck -> {
            log.warn("홈 한 줄 명령 실행 {} 이 {} 부터 진행이 없어 실패로 끝낸다", stuck.getId(), stuck.getUpdatedAt());
            stuck.fail(AssistantRunError.INTERNAL_ERROR);
        }));

    }

    /**
     * 서버가 꺼질 때 돌던 실행을 다시 줄 세운다
     * 서버가 1대라는 전제다 — 2대면 다른 서버가 돌리는 실행까지 되돌리므로 그때는 하트비트 방식으로 바꾼다
     */
    public void requeueRunning() {

        transaction.executeWithoutResult(status -> runs.findWithLockByRunningSinceIsNotNull()
                .forEach(AssistantRun::requeue));

    }

    // 자소서나 레포 정리를 이미 시작했다면 화면이 그 결과로 가는 버튼을 보이도록 실패가 아니라 끝맺음 말 없이 닫는다
    private static void closeAfterAiFailure(AssistantRun locked) {

        if (locked.hasSucceededStart()) {
            locked.finish(null);
            return;
        }

        locked.fail(AssistantRunError.AI_UNAVAILABLE);

    }

    // 한 번 부를 때마다 AI 에게 다음 행동을 새로 묻고, 같은 실행을 이어서 돌려야 하면 true 를 돌려준다
    private boolean advance(AssistantRun claimed) {

        Optional<AssistantRun> current = runs.findById(claimed.getId()).filter(row -> stillClaimed(claimed, row));
        if (current.isEmpty()) {
            return false;
        }

        AssistantRun run = current.get();
        AssistantStepReply reply = stepPort.next(requests.of(run));

        return switch (reply.action()) {
            case ASK_USER -> end(claimed, locked -> locked.ask(reply.say(), reply.askKind(), reply.options()));
            case FINISH -> end(claimed, locked -> locked.finish(reply.say()));
            case CALL_TOOL -> callTool(claimed, run, reply);
        };

    }

    // 걸음 상한에 닿은 뒤에는 도구를 실행하지 않고 줄도 남기지 않은 채 닫는다
    private boolean callTool(AssistantRun claimed, AssistantRun run, AssistantStepReply reply) {

        if (run.getStepsUsed() >= run.getStepLimit()) {
            return end(claimed, locked -> locked.finish(null));
        }

        ToolCall call = ToolCall.of(run, reply);
        Optional<Integer> seq = edit(claimed, locked -> locked.startStep(call));
        if (seq.isEmpty()) {
            return false;
        }

        ToolResult result = call.resolvable()
                ? tools.execute(run.getUserId(), seq.get(), call)
                : ToolResult.failed(ToolError.NOT_FOUND);

        return save(claimed, locked -> locked.finishStep(seq.get(), result));

    }

    private boolean end(AssistantRun claimed, Consumer<AssistantRun> change) {

        save(claimed, change);

        return false;

    }

    private boolean save(AssistantRun claimed, Consumer<AssistantRun> change) {

        return edit(claimed, locked -> {
            change.accept(locked);
            return Boolean.TRUE;
        }).isPresent();

    }

    // 집은 표가 그대로인 실행만 잠가 바꾸고, 그렇지 않으면 아무것도 하지 않고 빈 값을 돌려준다
    private <T> Optional<T> edit(AssistantRun claimed, Function<AssistantRun, T> change) {

        return transaction.execute(status -> runs.findWithLockById(claimed.getId())
                .filter(row -> stillClaimed(claimed, row))
                .map(change));

    }

    // 기다리는 사이 멈춘 실행으로 실패 처리되었거나 서버가 다시 떠 새로 집혔으면 집은 시각이 달라져, 이번 결과는 버린다
    private static boolean stillClaimed(AssistantRun claimed, AssistantRun row) {

        return row.getState() == AssistantRunState.RUNNING
                && claimed.getRunningSince().equals(row.getRunningSince());

    }
}
