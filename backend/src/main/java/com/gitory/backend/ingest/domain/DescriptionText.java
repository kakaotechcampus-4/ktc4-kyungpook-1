package com.gitory.backend.ingest.domain;

/** 저장소 설명 글을 DB 칸에 맞게 다듬으며, 글자 수는 칸이 세는 대로 코드 포인트로 센다 */
final class DescriptionText {

    private DescriptionText() {
    }

    /** 앞뒤 공백을 떼고, 남는 글이 없으면 null 이다 */
    static String stripped(String text) {

        if (text == null) {
            return null;
        }
        String stripped = text.strip();

        return stripped.isEmpty() ? null : stripped;

    }

    static int length(String text) {

        return text.codePointCount(0, text.length());

    }

    /** 이모지 같은 두 칸짜리 글자의 가운데를 자르지 않는다 */
    static String cut(String text, int maxLength) {

        if (text == null || length(text) <= maxLength) {
            return text;
        }

        return text.substring(0, text.offsetByCodePoints(0, maxLength));

    }
}
