package com.gitory.backend.assistant.infra;

import com.gitory.backend.assistant.domain.AssistantRunState;
import com.gitory.backend.consent.port.ActiveWorkPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사용자 답을 기다리는 실행은 돌고 있지 않으므로 RUNNING 만 센다 */
@Component
@RequiredArgsConstructor
public class AssistantActiveWorkAdapter implements ActiveWorkPort {

    private final AssistantRunRepository runs;

    @Override
    public boolean isRunning(Long userId) {

        return runs.existsByUserIdAndState(userId, AssistantRunState.RUNNING);

    }
}
