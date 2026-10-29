package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ai.AiProgress;
import com.gitory.backend.common.infra.ai.AiProgressItem;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class JobProgressRecorderTest {

    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final MovingClock clock = new MovingClock(Instant.parse("2026-10-27T00:00:00Z"));
    private final AnalysisJob job = AnalysisJob.enqueue(1L, 10L, "progress-key");
    private final JobActivityLog activity = mock(JobActivityLog.class);

    private JobProgressRecorder recorder;

    @BeforeEach
    void setUp() {

        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        given(manager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(jobs.findWithLockById(1L)).willReturn(Optional.of(job));
        job.start();
        recorder = new JobProgressRecorder(1L, jobs, new TransactionTemplate(manager), clock, activity);

    }

    @Test
    @DisplayName("처음 받은 진행과 단계가 바뀐 진행은 1초를 기다리지 않고 바로 단계에 남긴다")
    void writesFirstProgressAndStepChangeImmediately() {

        recorder.accept(new AiProgress("PR_REVIEW", 0, 4));
        recorder.accept(new AiProgress("COMMITS", 0, 30));

        assertThat(step(JobStepKey.PR_REVIEW)).isEqualTo(new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 4, 4));
        assertThat(step(JobStepKey.COMMITS)).isEqualTo(new JobStep(JobStepKey.COMMITS, JobStepState.RUNNING, 0, 30));

    }

    @Test
    @DisplayName("같은 단계의 진행이 1초 안에 다시 오면 남기지 않고, 1초가 지난 뒤 온 진행은 남긴다")
    void throttlesSameStepWithinOneSecond() {

        recorder.accept(new AiProgress("PR_REVIEW", 1, 4));
        clock.advance(Duration.ofMillis(500));
        recorder.accept(new AiProgress("PR_REVIEW", 2, 4));

        assertThat(step(JobStepKey.PR_REVIEW).done()).isEqualTo(1);

        clock.advance(Duration.ofMillis(500));
        recorder.accept(new AiProgress("PR_REVIEW", 3, 4));

        assertThat(step(JobStepKey.PR_REVIEW).done()).isEqualTo(3);

    }

    @Test
    @DisplayName("다 읽은 진행은 1초 안에 와도 마지막 수가 늦게 보이지 않게 바로 남긴다")
    void writesFinishedProgressWithinOneSecond() {

        recorder.accept(new AiProgress("PR_REVIEW", 1, 4));
        clock.advance(Duration.ofMillis(100));
        recorder.accept(new AiProgress("PR_REVIEW", 4, 4));

        assertThat(step(JobStepKey.PR_REVIEW)).isEqualTo(new JobStep(JobStepKey.PR_REVIEW, JobStepState.RUNNING, 4, 4));

    }

    @Test
    @DisplayName("모르는 단계 이름의 진행은 Job 을 읽지도 않고 버린다")
    void ignoresUnknownStep() {

        recorder.accept(new AiProgress("SOMETHING_NEW", 1, 2));

        verify(jobs, never()).findWithLockById(anyLong());

    }

    @Test
    @DisplayName("그사이 취소돼 RUNNING 이 아닌 Job 에는 진행을 남기지 않는다")
    void skipsJobThatIsNoLongerRunning() {

        job.cancel();

        recorder.accept(new AiProgress("PR_REVIEW", 1, 4));

        assertThat(step(JobStepKey.PR_REVIEW).done()).isZero();

    }

    @Test
    @DisplayName("진행을 쓰지 못해도 분석이 멈추지 않게 예외를 밖으로 던지지 않는다")
    void keepsGoingWhenWriteFails() {

        given(jobs.findWithLockById(1L)).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder.accept(new AiProgress("PR_REVIEW", 1, 4))).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("방금 읽은 줄은 진행 숫자를 1초 안에 다시 쓰지 않을 때도 모두 활동 기록에 넘기고, 모르는 단계의 줄은 넘기지 않는다")
    void passesEveryItemToActivityLogEvenWhileThrottled() {

        AiProgressItem first = new AiProgressItem("COMMIT", null, "첫 커밋", null, "aaaaaaa", 1, null, null, null, null);
        AiProgressItem second = new AiProgressItem("COMMIT", null, "둘째 커밋", null, "bbbbbbb", 2, null, null, null, null);

        recorder.accept(new AiProgress("COMMITS", 1, 4, first));
        clock.advance(Duration.ofMillis(500));
        recorder.accept(new AiProgress("COMMITS", 2, 4, second));
        recorder.accept(new AiProgress("SOMETHING_NEW", 1, 2, first));

        assertThat(step(JobStepKey.COMMITS).done()).isEqualTo(1);
        verify(activity).append(1L, JobStepKey.COMMITS, first);
        verify(activity).append(1L, JobStepKey.COMMITS, second);
        verifyNoMoreInteractions(activity);

    }

    private JobStep step(JobStepKey key) {

        return job.getSteps().stream().filter(step -> step.key() == key).findFirst().orElseThrow();

    }

    private static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {

            now = now.plus(duration);

        }

        @Override
        public ZoneId getZone() {

            return ZoneOffset.UTC;

        }

        @Override
        public Clock withZone(ZoneId zone) {

            return this;

        }

        @Override
        public Instant instant() {

            return now;

        }
    }
}
