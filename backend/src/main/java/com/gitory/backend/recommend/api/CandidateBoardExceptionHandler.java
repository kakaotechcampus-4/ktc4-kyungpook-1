package com.gitory.backend.recommend.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.recommend.domain.CandidateBoardNotFoundException;
import com.gitory.backend.recommend.domain.CandidateNotEditableException;
import com.gitory.backend.recommend.domain.CandidateNotFoundException;
import com.gitory.backend.recommend.domain.InvalidCandidateInputException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.recommend.api")
public class CandidateBoardExceptionHandler {

    @ExceptionHandler(CandidateBoardNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleRepositoryNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "저장소를 찾을 수 없습니다.");

    }

    @ExceptionHandler(CandidateNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCandidateNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "후보를 찾을 수 없습니다.");

    }

    @ExceptionHandler(CandidateNotEditableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCandidateNotEditable() {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, "이미 카드로 쓴 후보는 고칠 수 없습니다.");

    }

    /** 규칙마다 고칠 곳이 달라 도메인이 만든 메시지를 그대로 내려준다 */
    @ExceptionHandler(InvalidCandidateInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInput(InvalidCandidateInputException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage());

    }
}
