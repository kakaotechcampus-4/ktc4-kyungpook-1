package com.gitory.backend.job.domain;

/**
 * analysis_job.state — V2 CHECK 와 같은 5개
 * CANCELED 는 L 하나로 타이핑(CANCELLED 로 쓰면 DB CHECK 에 걸린다).
 * "부분 완료"는 상태가 아니라 partial 플래그다.
 */
public enum JobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
