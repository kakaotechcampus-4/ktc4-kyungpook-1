package com.gitory.backend.common.infra.ai;

/** AI 호출 한 번의 기록을 남기는 곳으로, 저장할 곳이 없는 시험은 NONE 을 쓴다 */
public interface AiCallRecorder {

    AiCallRecorder NONE = call -> { };

    void record(AiCall call);
}
