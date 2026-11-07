package com.gitory.backend.assistant.api;

import com.gitory.backend.assistant.domain.AssistantRunNotFoundException;
import com.gitory.backend.assistant.domain.AssistantRunStateException;
import com.gitory.backend.assistant.domain.InvalidAssistantInputException;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.assistant.api")
public class AssistantExceptionHandler {

    @ExceptionHandler(AssistantRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "실행을 찾을 수 없습니다.");

    }

    @ExceptionHandler(InvalidAssistantInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInput(InvalidAssistantInputException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage());

    }

    @ExceptionHandler(AssistantRunStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleState(AssistantRunStateException e) {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, e.getMessage());

    }
}
