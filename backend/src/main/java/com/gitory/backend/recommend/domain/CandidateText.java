package com.gitory.backend.recommend.domain;

/** 후보 글 검사를 한곳에 모아 회상 답과 직접 추가가 카드와 같은 판정을 쓰게 한다 */
final class CandidateText {

    static final int TITLE_MAX_LENGTH = 200;
    static final int REASON_MAX_LENGTH = 300;

    private CandidateText() {
    }

    /** 화면은 JS trim 으로 빈 글을 가르므로 NBSP·U+2007·U+202F·U+FEFF 만 든 글도 빈 글로 보고, 자바 isBlank 가 공백으로 보는 글자도 그대로 빈 글로 둔다 */
    static boolean isBlank(String text) {

        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);

    }

    /** 글이 없거나 비었거나 U+0000 이 있거나 UTF-16 글자 수가 maxLength 를 넘으면 거절하며, 받은 글은 고치지 않는다 */
    static void require(String text, String field, int maxLength) {

        if (text == null || isBlank(text)) {
            throw new InvalidCandidateInputException(field + " 가 비어 있습니다.");
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new InvalidCandidateInputException(field + " 에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        if (text.length() > maxLength) {
            throw new InvalidCandidateInputException(field + " 가 " + maxLength + "자를 넘습니다.");
        }

    }
}
