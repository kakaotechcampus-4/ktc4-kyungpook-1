package com.gitory.backend.agent.port;

import com.gitory.backend.common.infra.ai.AiProgress;

import java.util.function.Consumer;

public interface ExperienceGroupingPort {

    /**
     * 돌려준 후보 커밋은 모두 보낸 커밋이고 한 커밋은 한 후보에만 들어 있다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    ExperienceGroupingResponse group(ExperienceGroupingRequest request);

    /** {@link #group(ExperienceGroupingRequest)} 와 같고, AI 가 묶음 하나를 묶거나 이유를 붙일 때마다 진행을 progress 로 받는다 */
    default ExperienceGroupingResponse group(ExperienceGroupingRequest request, Consumer<AiProgress> progress) {

        return group(request);

    }
}
