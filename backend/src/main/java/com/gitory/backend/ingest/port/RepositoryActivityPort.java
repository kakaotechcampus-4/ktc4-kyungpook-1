package com.gitory.backend.ingest.port;

import com.gitory.backend.common.infra.ai.AiProgress;
import com.gitory.backend.consent.port.DiffEvidenceResult;

import java.util.function.Consumer;

public interface RepositoryActivityPort {

    CollectedActivity collect(IngestRequest request);

    /** {@link #collect(IngestRequest)} 와 같고, AI 가 PR·커밋을 하나 읽을 때마다 진행을 progress 로 받는다 */
    default CollectedActivity collect(IngestRequest request, Consumer<AiProgress> progress) {

        return collect(request);

    }

    /** 후보 커밋의 diff 를 AI 가 읽어 커밋별 근거로 요약하며, GitHub 저장소 정보는 연결 저장소 id 로 찾는다 */
    DiffEvidenceResult diffEvidence(DiffEvidenceRequest request);
}
