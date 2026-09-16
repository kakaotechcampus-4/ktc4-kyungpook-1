package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 인증 경계. 로그인 시작·콜백·로그아웃 경로와 "로그인 안 됐을 때 무엇을 돌려줄지"가 여기 있다.
 *
 * <h2>왜 consent 모듈 안에 있나</h2>
 * 보안 설정은 앱 전체에 걸리지만, 이 설정이 하는 일은 전부 consent 의 책임이다 —
 * GitHub 동의를 받고, 세션을 만들고, 그 세션이 없는 요청을 막는다. {@code common} 에 두면
 * common → consent(로그인 서비스) · consent → common(ApiResponse) 로 모듈 순환이 생겨
 * {@code ModuleBoundaryTest#noCyclicModuleDependencies} 가 깨진다.
 *
 * <h2>경로 (프론트 계약: frontend/docs/BACKEND_CONTRACT.md)</h2>
 * <pre>
 * GET  /api/auth/github/start     → 302 github.com/login/oauth/authorize
 * GET  /api/auth/github/callback  → 세션 생성 후 302 /      (실패·취소 시 302 /login?error=...)
 * POST /api/auth/logout           → 200 { data: { ok: true } }
 * </pre>
 *
 * <p>로그인하지 않은 {@code /api/**} 요청은 로그인 페이지로 리다이렉트하지 않고
 * <b>401 + UNAUTHENTICATED</b> 를 돌려준다. 프론트가 fetch 로 부르는 API 라
 * 302 를 내리면 로그인 HTML 이 JSON 파서에 들어간다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 프론트 라우트다. Spring 이 여기에 기본 로그인 페이지를 만들지 않게 이름만 알려 준다. */
    private static final String LOGIN_PAGE = "/login";

    /** 로그인 성공 후 돌아갈 곳. 프론트가 세션을 확인하고 화면을 정한다. */
    private static final String AFTER_LOGIN = "/";

    private static final String CALLBACK_PATH = "/api/auth/github/callback";
    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> githubLoginService) throws Exception {

        http
                .authorizeHttpRequests(authorize -> authorize
                        // 로그인 왕복 자체는 로그인 없이 되어야 한다.
                        .requestMatchers("/api/auth/**").permitAll()
                        // 헬스체크는 로드밸런서가 세션 없이 부른다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // /api 밖은 리버스 프록시가 프론트로 보낸다(HOSTING.md A안).
                        // 여기까지 오는 건 로컬 개발 정도라 막지 않는다.
                        .anyRequest().permitAll())

                // 쿠키 방식 CSRF. XSRF-TOKEN 쿠키를 프론트가 읽어 X-XSRF-TOKEN 헤더로 돌려준다
                // (frontend/src/api/client.ts). spa() 가 SPA 용 기본값 묶음이다 —
                // JS 가 읽을 수 있게 httpOnly 를 끄고, 로그인·로그아웃 뒤 토큰을 새로 발급한다.
                .csrf(csrf -> csrf.spa())

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticatedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))

                .oauth2Login(oauth2 -> oauth2
                        .loginPage(LOGIN_PAGE)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(new GithubStartRequestResolver(clientRegistrations)))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_PATH))
                        // 사용자·연결 저장이 여기서 일어난다. 토큰과 프로필이 함께 있는 유일한 지점이다.
                        .userInfoEndpoint(endpoint -> endpoint.userService(githubLoginService))
                        .successHandler(loginSuccessHandler())
                        .failureHandler(loginFailureHandler()))

                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))

                // 쓰지 않는 인증 방식은 켜 두지 않는다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }

    /**
     * 로그인 성공 → 무조건 {@code /} 로 보낸다.
     *
     * <p>기본 동작은 "원래 가려던 곳"으로 되돌리는 것인데, 여기서 원래 요청은 401 이 난
     * {@code /api/...} 호출이라 리다이렉트 대상이 될 수 없다. 어디로 돌아갈지는 프론트가
     * sessionStorage 로 기억한다(BACKEND_CONTRACT.md).
     */
    private AuthenticationSuccessHandler loginSuccessHandler() {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler(AFTER_LOGIN);
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }

    /**
     * 로그인 실패 → {@code /login?error=<코드>}.
     *
     * <p>사용자가 GitHub 동의 화면에서 취소하면 GitHub 이 {@code error=access_denied} 를 실어
     * 콜백으로 돌아오고, Spring 이 그 값을 그대로 {@link OAuth2AuthenticationException} 에 담는다.
     * 계약이 요구하는 {@code /login?error=access_denied} 가 이 경로다.
     *
     * <p>코드를 access_denied 로 고정하지 않는 이유는, 그러면 서버 설정 오류까지 "사용자가
     * 취소함"으로 보이기 때문이다. 분류되지 않은 실패는 {@code server_error} 로 접는다.
     * 예외 메시지는 URL 에 싣지 않는다 — 내부 사정이 주소창에 남는다.
     */
    private AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) -> {
            String code = "server_error";
            if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
                OAuth2Error error = oauth2Exception.getError();
                if (error != null && StringUtils.hasText(error.getErrorCode())) {
                    code = error.getErrorCode();
                }
            }
            response.sendRedirect(LOGIN_PAGE + "?error=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
        };
    }

    /**
     * 로그아웃 성공 → {@code 200 { data: { ok: true } }}.
     *
     * <p>204 가 아니라 200 + 봉투인 이유는 프론트가 모든 응답을 {@code { data, error }} 로
     * 파싱하기 때문이다(client.ts). 본문 없는 204 는 계약 위반으로 잡힌다.
     */
    private LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) ->
                writeJson(response, HttpStatus.OK, ApiResponse.ok(Map.of("ok", true)));
    }

    /** 세션이 없거나 만료됐다. 프론트는 이 코드를 보고 연결 단계로 되돌린다. */
    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, authException) -> writeJson(response, HttpStatus.UNAUTHORIZED,
                ApiResponse.fail(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다."));
    }

    /** 로그인은 됐는데 이 자원의 주인이 아니다. CSRF 토큰이 빠진 변경 요청도 여기로 온다. */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, deniedException) -> writeJson(response, HttpStatus.FORBIDDEN,
                ApiResponse.fail(ErrorCode.FORBIDDEN, "권한이 없습니다."));
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, ApiResponse<?> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
