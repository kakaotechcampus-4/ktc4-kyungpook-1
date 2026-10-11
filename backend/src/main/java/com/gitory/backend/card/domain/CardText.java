package com.gitory.backend.card.domain;

/** 카드 글 검사를 한곳에 모아 제목·기간·가리기 규칙·칸 글이 같은 판정을 쓰게 한다 */
final class CardText {

    private CardText() {
    }

    /** 화면은 JS trim 으로 빈 글을 가르므로 NBSP·U+2007·U+202F·U+FEFF 만 든 글도 빈 글로 보고, 자바 isBlank 가 공백으로 보는 글자도 그대로 빈 글로 둔다 */
    static boolean isBlank(String text) {

        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);

    }

    /** PostgreSQL 은 글에 U+0000 을 저장하지 못해 500 이 되므로, 저장하기 전에 400 으로 거절한다 */
    static void requireNoNul(String text, String field) {

        if (text.indexOf('\u0000') >= 0) {
            throw new InvalidCardInputException(field + "에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }

    }
}
