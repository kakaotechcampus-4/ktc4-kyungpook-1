package com.gitory.backend.card.port;

import java.util.UUID;

public interface CardGenerationPort {

    /** 카드의 초안을 만드는 Job 이 아직 대기 중이거나 실행 중이면 true 이고, 찾을 수 없는 Job 은 끝난 것으로 본다 */
    boolean isRunning(UUID jobId);

}
