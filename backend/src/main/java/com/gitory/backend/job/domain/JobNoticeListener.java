package com.gitory.backend.job.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Job 이 끝나면 사용자에게 알림을 남기며, 어떤 예외도 여기서 삼킨다
 * 알림은 덧붙는 일이라 못 남겨도 Job 의 결과와 같은 이벤트를 받는 다른 리스너(RepoRefreshFollowUp)가 그대로여야 한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobNoticeListener {

    private final JobNoticeRecorder recorder;

    @TransactionalEventListener
    public void jobEnded(JobEnded event) {

        try {
            recorder.cardsDrafted(event.jobId());
        } catch (RuntimeException failure) {
            log.warn("Job {} 이 끝났지만 카드 초안 알림을 남기지 못했다", event.jobId(), failure);
        }

    }

    @TransactionalEventListener
    public void jobFailed(JobFailed event) {

        try {
            recorder.analysisFailed(event.jobId());
        } catch (RuntimeException failure) {
            log.warn("Job {} 이 실패했지만 분석 실패 알림을 남기지 못했다", event.jobId(), failure);
        }

    }
}
