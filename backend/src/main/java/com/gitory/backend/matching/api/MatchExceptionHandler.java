package com.gitory.backend.matching.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.matching.domain.InvalidMatchingResponseException;
import com.gitory.backend.matching.domain.MatchingAiUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.matching.api")
public class MatchExceptionHandler {

    @ExceptionHandler(MatchingAiUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleAiUnavailable() {

        return ApiResponse.fail(ErrorCode.AI_UNAVAILABLE, "근거를 찾지 못했습니다. 잠시 뒤 다시 시도해 주세요.");

    }

    @ExceptionHandler(InvalidMatchingResponseException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleInvalidResponse() {

        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "요청을 정상적으로 처리하지 못하였습니다.");

    }
}
