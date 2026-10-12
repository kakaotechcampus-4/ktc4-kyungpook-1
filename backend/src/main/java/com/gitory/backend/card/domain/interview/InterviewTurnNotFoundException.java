package com.gitory.backend.card.domain.interview;

/** 내 카드에 요청한 번호의 질문이 없을 때 발생하는 에러 */
public class InterviewTurnNotFoundException extends RuntimeException {

    public InterviewTurnNotFoundException() {
        super("요청한 질문을 찾을 수 없습니다.");
    }
}
