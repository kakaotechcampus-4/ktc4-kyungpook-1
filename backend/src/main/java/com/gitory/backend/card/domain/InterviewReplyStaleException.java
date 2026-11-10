package com.gitory.backend.card.domain;

/** AI 가 답을 읽는 사이 카드의 현재 버전이 바뀌어 그 결과를 넣을 수 없을 때 발생하는 에러 */
public class InterviewReplyStaleException extends RuntimeException {

    public InterviewReplyStaleException() {
        super("답을 확인하는 사이 카드가 바뀌었습니다.");
    }
}
