package com.gitory.backend.consent.domain;

/**
 * 철회하지 않은 GitHub 연결이 없어 토큰을 쓸 수 없을 때 발생하는 에러
 */
public class GithubNotConnectedException extends RuntimeException {

    public GithubNotConnectedException() {
        super("GitHub 연결이 없거나 철회되었습니다.");
    }
}
