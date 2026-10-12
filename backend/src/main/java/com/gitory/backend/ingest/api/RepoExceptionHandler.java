package com.gitory.backend.ingest.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gitory.backend.ingest.api")
public class RepoExceptionHandler {

    /** 로그인은 됐지만 GitHub 권한이 없는 상황이라 401 이 아니라 403 으로 내려, 프론트가 재연동을 안내하게 한다 */
    @ExceptionHandler(GithubNotConnectedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleGithubNotConnected() {

        return ApiResponse.fail(ErrorCode.GITHUB_UNAUTHORIZED, "쓸 수 있는 GitHub 연결이 없습니다.");

    }

    @ExceptionHandler(ConnectedRepositoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleRepositoryNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "저장소를 찾을 수 없습니다.");

    }
}
