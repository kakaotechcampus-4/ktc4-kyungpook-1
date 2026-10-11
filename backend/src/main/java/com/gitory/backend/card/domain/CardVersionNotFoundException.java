package com.gitory.backend.card.domain;

/** 내 카드에 요청한 번호의 버전이 없을 때 발생하는 에러 */
public class CardVersionNotFoundException extends RuntimeException {

    public CardVersionNotFoundException() {
        super("요청한 카드 버전을 찾을 수 없습니다.");
    }
}
