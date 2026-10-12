package com.gitory.backend.consent.infra;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
enum GithubCountStop {
    RATE_LIMITED("GitHub 이 개수 세기를 한도로 거절해 저장소 {}개는 다음 조회 때 센다"),
    UNAUTHORIZED("GitHub 이 토큰을 거절해(401) 저장소 {}개는 다음 조회 때 센다"),
    TIME_LIMIT("개수 세기에 시간이 오래 걸려 저장소 {}개는 다음 조회 때 센다");

    final String message;
}
