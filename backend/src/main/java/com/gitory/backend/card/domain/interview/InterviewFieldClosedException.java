package com.gitory.backend.card.domain.interview;

/** 같은 칸을 이미 두 번 물었거나 넘김·측정 안 함으로 끝난 칸에 질문을 요청할 때 발생하는 에러 */
public class InterviewFieldClosedException extends RuntimeException {

    public InterviewFieldClosedException() {
        super("이 칸은 더 물을 수 없습니다.");
    }
}
