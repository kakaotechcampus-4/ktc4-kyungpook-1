package com.gitory.backend.consent.port;

import tools.jackson.databind.JsonNode;

/** 토큰 대신 범위가 고정된 수집 호출 권한을 제공한다. */
public interface GithubCollectionAccessPort {

    JsonNode collect(Long userId, GithubCollectionTarget target);
}
