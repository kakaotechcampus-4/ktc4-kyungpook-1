package com.gitory.backend.consent.port;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 토큰 대신 범위가 고정된 수집 호출 권한을 제공한다. */
public interface GithubCollectionAccessPort {

    JsonNode collect(Long userId, GithubCollectionTarget target, Instant since);

    /** 쓸 수 있는 GitHub 연결이 없거나 GitHub 이 토큰을 거절하면 GithubNotConnectedException 을 던진다 */
    List<GithubRepositoryResponse> repositories(Long userId);

    /** GitHub 이 세지 못한 저장소는 결과에서 빠진다 */
    List<GithubRepositoryCount> countActivity(Long userId, List<GithubCountTarget> targets);
}
