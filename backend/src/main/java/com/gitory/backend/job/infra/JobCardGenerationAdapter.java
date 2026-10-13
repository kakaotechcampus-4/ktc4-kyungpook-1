package com.gitory.backend.job.infra;

import com.gitory.backend.card.port.CardGenerationPort;
import com.gitory.backend.job.domain.JobState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** card 가 job 을 참조하지 않고 초안 Job 의 진행 여부를 묻도록 job 쪽에서 구현한다 */
@Component
@RequiredArgsConstructor
class JobCardGenerationAdapter implements CardGenerationPort {

    private final AnalysisJobRepository jobs;

    @Override
    public boolean isRunning(UUID jobId) {

        return jobs.existsByPublicIdAndStateIn(jobId, JobState.ACTIVE);

    }
}
