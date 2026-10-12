package com.gitory.backend.card.domain.interview;

/** 질문을 받을 칸이 S, T, A, R 중 하나가 아닐 때 발생하는 에러 */
public class InvalidInterviewFieldException extends RuntimeException {

    public InvalidInterviewFieldException() {
        super("field 는 S/T/A/R 중 하나여야 합니다.");
    }
}
