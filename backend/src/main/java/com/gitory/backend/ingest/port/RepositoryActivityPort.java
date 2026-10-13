package com.gitory.backend.ingest.port;

import com.gitory.backend.consent.port.DiffEvidenceResult;

public interface RepositoryActivityPort {

    CollectedActivity collect(IngestRequest request);

    /** 후보 커밋의 diff 를 AI 가 읽어 커밋별 근거로 요약하며, GitHub 저장소 정보는 연결 저장소 id 로 찾는다 */
    DiffEvidenceResult diffEvidence(DiffEvidenceRequest request);
}
