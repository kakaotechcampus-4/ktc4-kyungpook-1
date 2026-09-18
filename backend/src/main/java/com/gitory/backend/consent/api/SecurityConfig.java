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
 * 로그인·로그아웃 경로와 인증 실패 응답을 설정한다
 * API 는 프론트가 fetch 로 부르므로 미로그인 요청에 로그인 페이지 대신 401 JSON 을 준다
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String LOGIN_PAGE = "/login";

    private static final String AFTER_LOGIN = "/";

    private static final String CALLBACK_PATH = "/api/auth/github/callback";
    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 로그인 안 된 API 호출마다 세션 행이 쌓이지 않도록 requestCache 를 쓰지 않는다 */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> githubLoginService) throws Exception {

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().permitAll())

                .csrf(csrf -> csrf.spa())

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticatedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))

                .requestCache(cache -> cache.disable())

                .oauth2Login(oauth2 -> oauth2
                        .loginPage(LOGIN_PAGE)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(new GithubStartRequestResolver(clientRegistrations)))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_PATH))
                        .userInfoEndpoint(endpoint -> endpoint.userService(githubLoginService))
                        .successHandler(loginSuccessHandler())
                        .failureHandler(loginFailureHandler()))

                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))

                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }

    private AuthenticationSuccessHandler loginSuccessHandler() {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler(AFTER_LOGIN);
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }

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

    /** 프론트가 모든 응답을 { data, error } 로 읽으므로 204 대신 200 과 본문을 준다 */
    private LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) ->
                writeJson(response, HttpStatus.OK, ApiResponse.ok(Map.of("ok", true)));
    }

    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, authException) -> writeJson(response, HttpStatus.UNAUTHORIZED,
                ApiResponse.fail(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다."));
    }

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
