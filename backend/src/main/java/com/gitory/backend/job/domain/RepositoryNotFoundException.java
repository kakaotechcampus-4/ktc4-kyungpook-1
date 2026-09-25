package com.gitory.backend.job.domain;

/**
 * 요청한 레포가 없거나 나의 레포가 아닐 경우 발생하는 에러
 */
public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException() {
        super("요청한 레포를 찾을 수 없습니다.");
    }
}
