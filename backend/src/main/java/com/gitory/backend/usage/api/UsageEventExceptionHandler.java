package com.gitory.backend.usage.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.usage.domain.UsageEventRejectedException;
import com.gitory.backend.usage.domain.UsageEventRejection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.usage.api")
public class UsageEventExceptionHandler {

    private static final String REJECTED_MESSAGE = "받을 수 없는 지표 요청입니다.";

    // sendBeacon 은 응답을 보지 않아 거절을 로그로만 알아챈다
    // 보낸 값을 남기면 에러 알람 패턴과 겹칠 수 있어 이유 코드만 남기고, 이름은 소문자·숫자·밑줄 모양일 때만 예외에 실려 온다
    @ExceptionHandler(UsageEventRejectedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleRejected(UsageEventRejectedException e) {

        if (e.getLoggedName() == null) {
            log.warn("사용 지표를 거절했다: 이유={}", e.getReason());
        } else {
            log.warn("사용 지표를 거절했다: 이유={}, 이름={}", e.getReason(), e.getLoggedName());
        }

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, REJECTED_MESSAGE);

    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable() {

        log.warn("사용 지표를 거절했다: 이유={}", UsageEventRejection.BODY_UNREADABLE);

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, REJECTED_MESSAGE);

    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> handleUnsupportedMediaType() {

        log.warn("사용 지표를 거절했다: 이유={}", UsageEventRejection.UNSUPPORTED_MEDIA_TYPE);

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, REJECTED_MESSAGE);

    }
}
