package com.gitory.backend.coverletter.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.coverletter.domain.CoverLetterNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterStateException;
import com.gitory.backend.coverletter.domain.InvalidCoverLetterInputException;
import com.gitory.backend.coverletter.domain.NoConfirmedCardsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.coverletter.api")
public class CoverLetterExceptionHandler {

    @ExceptionHandler(CoverLetterNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "자소서를 찾을 수 없습니다.");

    }

    @ExceptionHandler(InvalidCoverLetterInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInput(InvalidCoverLetterInputException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage());

    }

    @ExceptionHandler(CoverLetterStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleState(CoverLetterStateException e) {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, e.getMessage());

    }

    @ExceptionHandler(NoConfirmedCardsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiResponse<Void> handleNoConfirmedCards() {

        return ApiResponse.fail(ErrorCode.NO_CONFIRMED_CARDS, "확정한 카드가 없어 자소서를 만들 수 없습니다.");

    }
}
