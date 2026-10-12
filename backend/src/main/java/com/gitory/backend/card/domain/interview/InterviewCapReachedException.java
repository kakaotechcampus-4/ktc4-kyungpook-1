package com.gitory.backend.card.domain.interview;

/** 만든 질문 수가 상한에 닿은 카드에 새 질문을 요청할 때 발생하는 에러 */
public class InterviewCapReachedException extends RuntimeException {

    public InterviewCapReachedException() {
        super("이 카드에서 받을 수 있는 질문을 모두 사용했습니다.");
    }
}
