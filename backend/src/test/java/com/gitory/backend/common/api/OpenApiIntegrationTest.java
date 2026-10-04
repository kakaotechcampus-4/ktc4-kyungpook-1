package com.gitory.backend.common.api;

import com.gitory.backend.consent.domain.StubGithubConfig;
import com.gitory.backend.support.TestBrowser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서가 실제 필터 체인과 MVC 라우팅에서 열리되 세션·CSRF 보호는 유지되는지 검사한다. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api/docs",
        "springdoc.swagger-ui.enabled=true",
        "springdoc.swagger-ui.path=/api/swagger-ui.html",
        "springdoc.swagger-ui.csrf.enabled=true",
        "springdoc.swagger-ui.csrf.cookie-name=XSRF-TOKEN",
        "springdoc.swagger-ui.csrf.header-name=X-XSRF-TOKEN"
})
@AutoConfigureMockMvc
@Import(StubGithubConfig.class)
@Testcontainers
class OpenApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("OpenAPI 는 실제 endpoint·응답 봉투·세션 인증을 기록하고 세션 principal은 숨긴다")
    void generatedSpecMatchesImplementedApi() throws Exception {
        String document = mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Gitory Backend API"))
                .andExpect(jsonPath("$.paths['/api/me'].get.summary").value("내 정보 조회"))
                .andExpect(jsonPath("$.paths['/api/me'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/jobs'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/jobs/{id}'].get.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/jobs/{id}'].get.parameters[0].schema.format").value("uuid"))
                .andExpect(jsonPath("$.paths['/api/repos/{id}/analyze'].post.parameters.length()").value(2))
                .andExpect(jsonPath("$.paths['/api/repos/{id}/analyze'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/cards']").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.sessionCookie.in").value("cookie"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionCookie.name").value("SESSION"))
                .andExpect(jsonPath("$.security[0].sessionCookie").isArray())
                .andExpect(jsonPath("$.components.schemas.ApiResponseMeView.properties.data").exists())
                .andExpect(jsonPath("$.components.schemas.ApiResponseMeView.properties.error").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(document).doesNotContain("LoginUser", "test-client-secret", StubGithubConfig.ACCESS_TOKEN);
    }

    @Test
    @DisplayName("Swagger UI·assets·설정이 /api 아래에서 열리고 CSRF 자동 전달 설정이 노출된다")
    void uiStaysUnderApiPrefix() throws Exception {
        String redirect = mvc.perform(get("/api/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("/api/swagger-ui/index.html");
        mvc.perform(get("/api/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
        mvc.perform(get("/api/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/docs/swagger-config")))
                // springdoc 3.1.1 은 CSRF 설정을 swagger-config JSON 대신 initializer 의 interceptor 에 삽입한다.
                .andExpect(content().string(containsString("requestInterceptor")))
                .andExpect(content().string(containsString("XSRF-TOKEN")))
                .andExpect(content().string(containsString("X-XSRF-TOKEN")));
        mvc.perform(get("/api/docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configUrl").value("/api/docs/swagger-config"));
    }

    @Test
    @DisplayName("AI 문서 화면은 동일 서버 assets 를 사용하고 모든 Try it out 을 비활성화한다")
    void aiViewerIsReadOnlyAndUsesBundledAssets() throws Exception {
        mvc.perform(get("/api/ai-docs.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("url: \"/api/docs/ai\"")))
                .andExpect(content().string(containsString("supportedSubmitMethods: []")))
                .andExpect(content().string(containsString("persistAuthorization: false")))
                .andExpect(content().string(containsString("/api/swagger-ui/swagger-ui.css")))
                .andExpect(content().string(containsString("/api/swagger-ui/swagger-ui-bundle.js")));
        mvc.perform(get("/api/swagger-ui/swagger-ui.css"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/swagger-ui/swagger-ui-bundle.js"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/docs"))
                .andExpect(jsonPath("$.urls").doesNotExist());
    }

    @Test
    @DisplayName("문서를 공개해도 실제 API 는 세션·CSRF 없이 호출할 수 없다")
    void publicDocsDoNotBypassApiSecurity() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/repos/not-a-uuid/analyze"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        TestBrowser browser = new TestBrowser(mvc);
        String start = browser.perform(get("/api/auth/github/start"))
                .andReturn().getResponse().getRedirectedUrl();
        browser.perform(get("/api/auth/github/callback")
                        .param("code", "stub-authorization-code")
                        .param("state", TestBrowser.queryOf(start).get("state")))
                .andExpect(status().is3xxRedirection());
        browser.perform(get("/api/jobs"))
                .andExpect(status().isOk());
        browser.perform(post("/api/repos/not-a-uuid/analyze"))
                .andExpect(status().isForbidden());
        browser.perform(post("/api/repos/not-a-uuid/analyze")
                        .header("X-XSRF-TOKEN", browser.csrfToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
