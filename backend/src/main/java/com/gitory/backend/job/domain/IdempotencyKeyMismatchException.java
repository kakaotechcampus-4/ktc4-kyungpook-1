package com.gitory.backend.job.domain;

/**
 * 같은 멱등키로 다른 레포 분석을 요청할 경우 다른 레포의 Job 을 돌려주면 안 되므로 거절한다.
 */
public class IdempotencyKeyMismatchException extends RuntimeException{

    public IdempotencyKeyMismatchException() {
        super("같은 Idempotency-Key 가 다른 레포 분석에 이미 쓰였다");
    }

}
