package com.gitory.backend.card.domain.interview;

/** AI 가 잠시 응답하지 못해 질문을 받지 못했고, 질문은 만들어지지 않아 다시 요청해도 될 때 발생하는 에러 */
public class InterviewAiUnavailableException extends RuntimeException {

    public InterviewAiUnavailableException(Throwable cause) {
        super("AI 가 잠시 응답하지 못해 질문을 받지 못했습니다.", cause);
    }
}
