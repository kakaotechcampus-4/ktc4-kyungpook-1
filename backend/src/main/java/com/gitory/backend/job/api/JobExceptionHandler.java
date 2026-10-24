package com.gitory.backend.job.api;

import com.gitory.backend.card.domain.CandidateAlreadyUsedException;
import com.gitory.backend.card.domain.CardConfirmedException;
import com.gitory.backend.card.domain.CardDraftNotRetryableException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.job.domain.IdempotencyKeyMismatchException;
import com.gitory.backend.job.domain.JobNotFoundException;
import com.gitory.backend.job.domain.NoCandidatesException;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
import com.gitory.backend.job.domain.TooManyCandidatesException;
import com.gitory.backend.recommend.domain.CandidateNotFoundException;
import com.gitory.backend.recommend.domain.CandidateNotUsableException;
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

    @ExceptionHandler(GithubNotConnectedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleGithubNotConnected() {

        return ApiResponse.fail(ErrorCode.GITHUB_UNAUTHORIZED, "쓸 수 있는 GitHub 연결이 없습니다.");

    }

    @ExceptionHandler(CardNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCardNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");

    }

    @ExceptionHandler(CardConfirmedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCardConfirmed() {

        return ApiResponse.fail(ErrorCode.CARD_CONFIRMED, "확정된 카드는 다시 열어야 수정할 수 있습니다.");

    }

    @ExceptionHandler(CardDraftNotRetryableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDraftNotRetryable() {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, "다시 초안을 만들 수 있는 카드가 아닙니다.");

    }

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleJobNotFound() {
        return ApiResponse.fail(ErrorCode.NOT_FOUND, "Job 을 찾을 수 없습니다.");
    }

    @ExceptionHandler(NoCandidatesException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleNoCandidates() {

        return ApiResponse.fail(ErrorCode.NO_CANDIDATES, "카드로 만들 후보를 하나 이상 골라 주세요.");

    }

    @ExceptionHandler(TooManyCandidatesException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTooManyCandidates(TooManyCandidatesException e) {

        return ApiResponse.fail(ErrorCode.INVALID_REQUEST, "카드는 한 번에 " + e.max() + "개까지 만들 수 있습니다.");

    }

    @ExceptionHandler(CandidateNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleCandidateNotFound() {

        return ApiResponse.fail(ErrorCode.NOT_FOUND, "후보를 찾을 수 없습니다.");

    }

    @ExceptionHandler({CandidateNotUsableException.class, CandidateAlreadyUsedException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleCandidateNotUsable() {

        return ApiResponse.fail(ErrorCode.INVALID_STATE, "이미 카드로 쓴 후보이거나 제외한 후보가 있습니다.");

    }
}