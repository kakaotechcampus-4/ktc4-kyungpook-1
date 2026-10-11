package com.gitory.backend.consent.domain;

/**
 * 쓸 수 있는 GitHub 연결이 없어 GitHub 을 부를 수 없을 때 발생하는 에러
 */
public class GithubNotConnectedException extends RuntimeException {

    public GithubNotConnectedException() {
        super("쓸 수 있는 GitHub 연결이 없습니다.");
    }
}
