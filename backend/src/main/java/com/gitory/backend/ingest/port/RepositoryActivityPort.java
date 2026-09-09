package com.gitory.backend.ingest.port;

/**
 * GitHub 을 보는 유일한 창구다. 구현은 {@code ingest.infra} 에 있고 모듈 밖에서
 * 참조할 수 없다 — 분석 범위 제한(ADR-0003)이 여러 곳으로 흩어지지 않게 한다.
 */
public interface RepositoryActivityPort {

    /**
     * 스냅샷 하나를 수집한다.
     *
     * <p>구현은 반드시 다음을 지킨다.
     * <ul>
     *   <li>{@code scope} 에 없는 저장소를 건드리지 않는다.</li>
     *   <li>기본 브랜치만 읽지 않는다 — 수업 레포에서 {@code main} 기준 본인 커밋 0개,
     *       본인 브랜치 기준 26개가 나왔다.</li>
     *   <li>커밋 1,000 · PR 50 · Issue 50 을 넘으면 버리지 않고 {@code partial} 로 표시한다.</li>
     *   <li>1MB 초과 diff 와 바이너리는 원문 없이 메타데이터만 남긴다.</li>
     * </ul>
     */
    IngestResult collect(IngestRequest request);
}
