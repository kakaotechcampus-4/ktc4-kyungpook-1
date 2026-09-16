package com.gitory.backend.consent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 로그인 시작 경로를 {@code GET /api/auth/github/start} 하나로 고정한다.
 *
 * <p>Spring 의 기본 resolver 는 경로 끝에서 registrationId 를 읽는다
 * ({@code /oauth2/authorization/{registrationId}}). 프론트 계약(BACKEND_CONTRACT.md)이
 * 정한 경로는 {@code .../github/start} 라 끝이 registrationId 가 아니어서 기본 규칙으로는
 * 매칭되지 않는다.
 *
 * <p>리다이렉트를 한 번 더 태워 기본 경로로 넘길 수도 있었지만, 그러면 로그인 진입점이
 * 두 개(계약 경로 + Spring 기본 경로)가 된다. 여기서 registrationId 를 직접 지정해
 * 진입점을 하나로 둔다 — {@code resolve(request, registrationId)} 는 요청 URL 을 보지 않고
 * 넘겨받은 id 로 등록 정보를 찾는다.
 *
 * <p>공급자가 GitHub 하나뿐이라 id 를 상수로 둔다. 둘째 공급자가 생기면 그때 경로에
 * 공급자 이름을 넣고 이 클래스를 지우는 게 맞다.
 */
final class GithubStartRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String START_PATH = "/api/auth/github/start";

    private static final String REGISTRATION_ID = "github";

    private final RequestMatcher startMatcher =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, START_PATH);

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    GithubStartRequestResolver(ClientRegistrationRepository clientRegistrations) {
        // 기본 base URI 는 쓰지 않는다. 아래 resolve 가 매칭을 직접 하고,
        // 두 인자짜리 resolve 는 base URI 와 무관하게 동작한다.
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrations, DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    /**
     * 이 filter 는 모든 요청에 대해 불린다. null 을 돌려주면 "로그인 시작이 아니다"라는 뜻이라
     * 요청이 그대로 다음 filter 로 간다.
     */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return startMatcher.matches(request) ? delegate.resolve(request, REGISTRATION_ID) : null;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return delegate.resolve(request, clientRegistrationId);
    }
}
