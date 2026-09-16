package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.domain.MeQueryService;
import com.gitory.backend.consent.domain.MeView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 자신. 프론트가 앱을 띄울 때 제일 먼저 부르고, 401 이면 로그인 화면으로 간다.
 *
 * <p>사용자 id 를 파라미터로 받지 않는다 — 세션이 곧 "누구인지"다. 경로에 id 가 있으면
 * 남의 id 를 넣어 보는 시도가 가능해지고, 그걸 막는 코드가 또 필요해진다.
 */
@RestController
public class MeController {

    private final MeQueryService meQuery;

    public MeController(MeQueryService meQuery) {
        this.meQuery = meQuery;
    }

    @GetMapping("/api/me")
    public ApiResponse<MeView> me(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(meQuery.load(loginUser.id(), loginUser.avatarUrl()));
    }
}
