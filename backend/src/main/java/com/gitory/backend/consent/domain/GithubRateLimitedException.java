package com.gitory.backend.consent.domain;

/**
 * GitHub 이 사용 한도 때문에 요청을 거절했을 때 발생하는 에러
 */
public class GithubRateLimitedException extends RuntimeException {

    public GithubRateLimitedException() {
        super("GitHub 요청 한도에 걸렸습니다.");
    }
}
