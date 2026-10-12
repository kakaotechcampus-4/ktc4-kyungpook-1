package com.gitory.backend.card.domain.interview;

/**
 * AI 의 응답을 질문으로 쓸 수 없을 때 발생하는 에러
 * 처리기를 따로 두지 않아 전역 처리기가 원인을 남기고 500 INTERNAL_ERROR 로 답한다
 */
public class InvalidInterviewResponseException extends RuntimeException {

    public InvalidInterviewResponseException(String message) {
        super(message);
    }

    public InvalidInterviewResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
