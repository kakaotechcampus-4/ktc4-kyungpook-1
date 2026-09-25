package com.gitory.backend.job.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.job.domain.IdempotencyKeyMismatchException;
import com.gitory.backend.job.domain.JobNotFoundException;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.job.api")
public class JobExceptionHandler {

    @ExceptionHandler(RepositoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleRepositoryNotFound() {
        return ApiResponse.fail(ErrorCode.NOT_FOUND, "레포를 찾을 수 없습니다.");
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIdempotencyKeyMismatch() {
        return ApiResponse.fail(ErrorCode.IDEMPOTENCY_KEY_MISMATCH, "이미 다른 레포 분석에 쓰인 요청 키입니다.");
    }

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleJobNotFound() {
        return ApiResponse.fail(ErrorCode.NOT_FOUND, "Job 을 찾을 수 없습니다.");
    }
}