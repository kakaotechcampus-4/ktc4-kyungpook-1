package com.gitory.backend.common.api;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 내부 AI의 명세만 읽어 Swagger에 전달한다. AI 실행 엔드포인트는 공개하지 않는다. */
@Hidden
@RestController
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class AiDocumentationController {

    private static final String READ_ONLY_VIEWER = """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Gitory AI API 문서</title>
              <link rel="stylesheet" href="/api/swagger-ui/swagger-ui.css">
              <style>
                body { margin: 0; background: #fafafa; }
                nav { padding: 20px; font-family: sans-serif; border-bottom: 1px solid #ddd; }
                nav p { margin: 10px 0 0; }
                nav a { margin-right: 16px; }
              </style>
            </head>
            <body>
              <nav aria-label="API 문서 안내">
                <a href="/api/swagger-ui.html">Backend API 문서</a>
                <a href="/api/docs/ai">AI OpenAPI JSON</a>
                <p>AI API는 내부 서버 간 호출용입니다. 이 화면은 읽기 전용이며 실행 버튼을 제공하지 않습니다.</p>
                <p>로컬 실행 검증은 AI 서버의 /docs에서 수행하세요.</p>
              </nav>
              <div id="swagger-ui"></div>
              <script src="/api/swagger-ui/swagger-ui-bundle.js"></script>
              <script>
                window.onload = function () {
                  window.ui = SwaggerUIBundle({
                    url: "/api/docs/ai",
                    dom_id: "#swagger-ui",
                    presets: [SwaggerUIBundle.presets.apis],
                    supportedSubmitMethods: [],
                    persistAuthorization: false,
                    validatorUrl: null,
                    deepLinking: true
                  });
                };
              </script>
            </body>
            </html>
            """;

    private final AiHttpClient client;

    public AiDocumentationController(AiHttpClient client) {
        this.client = client;
    }

    /** AI 요청을 BE origin 에서 실행하지 않도록 별도의 읽기 전용 Swagger 화면을 제공한다. */
    @GetMapping(value = "/api/ai-docs.html", produces = "text/html;charset=UTF-8")
    public String aiDocumentViewer() {
        return READ_ONLY_VIEWER;
    }

    @GetMapping("/api/docs/ai")
    public ResponseEntity<?> aiOpenApi() {
        try {
            JsonNode document = client.get("/openapi.json");
            if (!document.path("openapi").isString() || !document.path("paths").isObject()) {
                return unavailable();
            }
            return ResponseEntity.ok(document);
        } catch (AiClientException failure) {
            return unavailable();
        }
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR,
                        "AI API 문서를 불러오지 못했습니다. AI 서버와 AI_DOCS_ENABLED 설정을 확인하세요."));
    }
}
