package com.gitory.backend.card.domain;

/** 요청한 활동이 없거나 내 활동이 아닐 때 발생하는 에러 */
public class ActivityNotFoundException extends RuntimeException {

    public ActivityNotFoundException() {
        super("요청한 활동을 찾을 수 없습니다.");
    }
}
