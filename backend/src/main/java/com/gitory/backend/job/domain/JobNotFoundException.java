package com.gitory.backend.job.domain;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException() {
        super("요청한 Job 을 찾을 수 없습니다.");
    }
}
