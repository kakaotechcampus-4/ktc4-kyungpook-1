package com.gitory.backend.common.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 구현된 Spring API 만 문서화한다. 계획 중인 카드·인터뷰 API 는 컨트롤러 구현 후 자동으로 추가된다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    static final String SESSION_AUTH = "sessionCookie";

    @Bean
    OpenAPI backendOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Gitory Backend API")
                        .version("0.0.1")
                        .description("""
                                현재 구현된 BE API의 문서입니다. 성공·실패 응답은 `{data, error}` 형식입니다.

                                인증은 GitHub OAuth 후 발급되는 HttpOnly `SESSION` 쿠키를 사용합니다.
                                같은 브라우저에서 `/api/auth/github/start`로 로그인한 뒤 Swagger로 돌아오세요.
                                브라우저는 세션 쿠키를 자동으로 전송하므로 Authorize에 토큰을 입력하지 않습니다.
                                POST 등 변경 요청에는 `XSRF-TOKEN` 쿠키 값이 `X-XSRF-TOKEN` 헤더로 필요하며,
                                Swagger UI가 이 값을 자동으로 전달합니다. CSRF 검증은 문서에서도 유지됩니다.

                                AI 계약은 [AI API 읽기 전용 문서](/api/ai-docs.html)에서 확인하세요.
                                해당 화면은 실행 버튼을 제공하지 않으며, AI 내부 API를 공개 프록시하지 않습니다.

                                분석 요청 API는 Job 접수까지 구현되어 있습니다. 현재 실행 워커가 없어
                                접수 성공이 수집·분석 완료를 의미하지 않으며 Job이 QUEUED에 머무를 수 있습니다.
                                """))
                // 상대 주소를 사용해 localhost 와 HTTPS 리버스 프록시에서 같은 문서를 쓴다.
                .addServersItem(new Server().url("/"))
                .components(new Components().addSecuritySchemes(SESSION_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("SESSION")
                                .description("GitHub OAuth 로그인 후 브라우저가 자동으로 보내는 HttpOnly 세션 쿠키")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_AUTH));
    }
}
