package com.gitory.backend.card.domain.interview;

/** 이미 답한 질문에 저장된 답과 다른 답이 올 때 발생하는 에러 */
public class InterviewTurnAnsweredException extends RuntimeException {

    public InterviewTurnAnsweredException() {
        super("이미 다른 답을 한 질문입니다.");
    }
}
