package com.gitory.backend.common.infra.ratelimit;

/** 사용자별 호출 한도를 넘었다. 기다릴 초는 Retry-After 와 오류 응답에 그대로 내려간다. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSec;

    public RateLimitExceededException(long retryAfterSec) {
        super("호출 한도를 넘었다");
        this.retryAfterSec = retryAfterSec;
    }

    public long retryAfterSec() {

        return retryAfterSec;

    }
}
