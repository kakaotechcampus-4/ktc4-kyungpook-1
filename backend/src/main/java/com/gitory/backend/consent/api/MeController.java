package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.domain.MeQueryService;
import com.gitory.backend.consent.domain.MeView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자 정보를 돌려준다.
 * 남의 id 로 조회하지 못하도록 id 는 경로가 아닌 세션에서 꺼낸다. */
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
