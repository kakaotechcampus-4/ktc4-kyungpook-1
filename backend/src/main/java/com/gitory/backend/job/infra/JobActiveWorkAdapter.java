package com.gitory.backend.job.infra;

import com.gitory.backend.consent.port.ActiveWorkPort;
import com.gitory.backend.job.domain.JobState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 분석과 카드 초안 Job 이 같은 테이블이라 종류를 가리지 않고 줄 서 있거나 도는 Job 을 센다 */
@Component
@RequiredArgsConstructor
public class JobActiveWorkAdapter implements ActiveWorkPort {

    private final AnalysisJobRepository jobs;

    @Override
    public boolean isRunning(Long userId) {

        return jobs.existsByUserIdAndStateIn(userId, JobState.ACTIVE);

    }
}
