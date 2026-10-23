package com.gitory.backend.common.api;

import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 한도는 어느 모듈의 요청에서나 걸리므로 모듈별 처리기가 아니라 여기서 한 번에 429 로 바꾼다
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitExceededException e) {

        long retryAfterSec = e.retryAfterSec();
        long minutes = (retryAfterSec + 59) / 60;

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSec))
                .body(ApiResponse.fail(ErrorCode.RATE_LIMITED, "요청이 많아요. " + minutes + "분 뒤에 다시 시도해 주세요.",
                        Map.of("retryAfterSec", retryAfterSec)));

    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
        log.error("처리하지 못한 전역 예외", e);
        return handleExceptionInternal(e, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return super.handleExceptionInternal(e, ApiResponse.fail(codeOf(status), messageOf(status)), headers, status, request);
    }

    private static ErrorCode codeOf(HttpStatusCode status) {

        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }

        return status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    }

    private static String messageOf(HttpStatusCode status) {

        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return "요청한 경로를 찾을 수 없습니다.";
        }

        return status.is4xxClientError() ? "처리할 수 없는 요청입니다." : "요청을 정상적으로 처리하지 못하였습니다.";
    }
}
