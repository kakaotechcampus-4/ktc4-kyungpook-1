package com.gitory.backend.card.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 카드에 붙일 저장소를 못 찾는 일은 직접 작성 시작에서만 생겨서, 카드 공통 처리기가 아니라 그 컨트롤러에만 건다 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ManualCardController.class)
public class ManualCardExceptionHandler {

    @ExceptionHandler(ConnectedRepositoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleRepositoryNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "저장소를 찾을 수 없습니다.");

    }
}
