package com.gitory.backend.consent.port;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** 토큰 대신 범위가 고정된 수집 호출 권한을 제공한다. */
public interface GithubCollectionAccessPort {

    JsonNode collect(Long userId, GithubCollectionTarget target);

    /** 쓸 수 있는 GitHub 연결이 없으면 GithubNotConnectedException 을 던진다 */
    List<GithubRepositoryResponse> repositories(Long userId);
}
