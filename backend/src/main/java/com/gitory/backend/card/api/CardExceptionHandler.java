package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.ActivityNotFoundException;
import com.gitory.backend.card.domain.CardConfirmedException;
import com.gitory.backend.card.domain.CardGeneratingException;
import com.gitory.backend.card.domain.CardNotConfirmableException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.CardVersionNotFoundException;
import com.gitory.backend.card.domain.InvalidCardInputException;
import com.gitory.backend.card.domain.interview.InterviewAiUnavailableException;
import com.gitory.backend.card.domain.interview.InterviewCapReachedException;
import com.gitory.backend.card.domain.interview.InterviewFieldClosedException;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.InvalidInterviewFieldException;
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

    @ExceptionHandler(ActivityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleActivityNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "활동을 찾을 수 없습니다.");

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

    @ExceptionHandler(CardGeneratingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCardGenerating() {

        return ApiResponse.fail(ErrorCode.INVALID_STATE,
                "초안을 만드는 중인 카드입니다. 초안이 만들어진 뒤에 다시 시도해 주세요.");

    }

    @ExceptionHandler(CardNotConfirmableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiResponse<Void> handleNotConfirmable() {

        return ApiResponse.fail(ErrorCode.NOT_CONFIRMABLE, "상황과 행동을 채워야 확정할 수 있습니다.");

    }

    @ExceptionHandler(InterviewFieldClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleInterviewFieldClosed() {

        return ApiResponse.fail(ErrorCode.INTERVIEW_FIELD_CLOSED, "이 칸은 더 물을 수 없습니다. 칸 내용은 직접 고칠 수 있어요.");

    }

    @ExceptionHandler(InterviewCapReachedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleInterviewCap() {

        return ApiResponse.fail(ErrorCode.INTERVIEW_CAP, "이 카드에서 받을 수 있는 질문을 모두 사용했습니다.");

    }

    @ExceptionHandler(InvalidInterviewFieldException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInterviewField() {

        return ApiResponse.fail(ErrorCode.BAD_FIELD, "field 는 S/T/A/R 중 하나여야 합니다.");

    }

    @ExceptionHandler(InterviewAiUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleInterviewAiUnavailable() {

        return ApiResponse.fail(ErrorCode.AI_UNAVAILABLE, "질문을 만들지 못했습니다. 잠시 뒤 다시 시도해 주세요.");

    }

    @ExceptionHandler(InterviewTurnNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleInterviewTurnNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "질문을 찾을 수 없습니다.");

    }

    @ExceptionHandler(InterviewTurnAnsweredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleInterviewTurnAnswered() {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, "이미 다른 답을 한 질문입니다.");

    }

    /** 규칙마다 고칠 곳이 달라 도메인이 만든 메시지를 그대로 내려준다 */
    @ExceptionHandler(InvalidCardInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidInput(InvalidCardInputException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage());

    }
}
