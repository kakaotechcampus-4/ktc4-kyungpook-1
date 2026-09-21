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
 * 로그인 시작 경로가 Spring 기본 규칙(/oauth2/authorization/{id})과 달라
 * /api/auth/github/start 요청을 github 등록 정보로 직접 연결한다
 */

final class GithubStartRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String START_PATH = "/api/auth/github/start";

    private static final String REGISTRATION_ID = "github";

    private final RequestMatcher startMatcher =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, START_PATH);

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    GithubStartRequestResolver(ClientRegistrationRepository clientRegistrations) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrations, DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return startMatcher.matches(request) ? delegate.resolve(request, REGISTRATION_ID) : null;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return delegate.resolve(request, clientRegistrationId);
    }
}
