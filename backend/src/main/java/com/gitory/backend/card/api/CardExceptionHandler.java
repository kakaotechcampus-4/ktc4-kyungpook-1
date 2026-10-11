package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardConfirmedException;
import com.gitory.backend.card.domain.CardNotConfirmableException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.CardVersionNotFoundException;
import com.gitory.backend.card.domain.InvalidCardInputException;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.card.api")
public class CardExceptionHandler {

    @ExceptionHandler(CardNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCardNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");

    }

    @ExceptionHandler(CardVersionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCardVersionNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "카드 버전을 찾을 수 없습니다.");

    }

    @ExceptionHandler(CardConfirmedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCardConfirmed() {

        return ApiResponse.fail(ErrorCode.CARD_CONFIRMED, "확정된 카드는 다시 열어야 수정할 수 있습니다.");

    }

    @ExceptionHandler(CardNotConfirmableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiResponse<Void> handleNotConfirmable() {

        return ApiResponse.fail(ErrorCode.NOT_CONFIRMABLE, "상황과 행동을 채워야 확정할 수 있습니다.");

    }

    /** 규칙마다 고칠 곳이 달라 도메인이 만든 메시지를 그대로 내려준다 */
    @ExceptionHandler(InvalidCardInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInput(InvalidCardInputException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage());

    }
}
