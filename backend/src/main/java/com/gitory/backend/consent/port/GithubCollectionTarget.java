package com.gitory.backend.consent.port;

import java.util.List;

/** 소유권을 확인한 연결 저장소. 토큰·사용자 입력의 OAuth 값은 갖지 않는다. */
public record GithubCollectionTarget(Long userRepositoryId, GithubCollectionRepo repository, List<String> branches) {
}
