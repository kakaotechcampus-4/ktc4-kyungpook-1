package com.gitory.backend.coverletter.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 사용자가 고친 초안의 글자 수와 남은 빈칸을 AI 와 같은 기준으로 센다
 * 빈칸 문구는 AI 의 cover_letter_check.blank() 와 글자 그대로 같아야 하며, 다르면 고친 글의 빈칸을 찾지 못한다
 */
final class DraftText {

    private static final Map<MaterialItem, String> BLANKS = new EnumMap<>(Map.of(
            MaterialItem.SITUATION, "[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]",
            MaterialItem.ACTION, "[행동: 직접 한 일을 적어 주세요]",
            MaterialItem.RESULT, "[결과: 바뀐 점이나 숫자를 적어 주세요]",
            MaterialItem.LEARNING, "[배운 점: 배운 점을 적어 주세요]",
            MaterialItem.TRIGGER, "[계기: 시작한 계기를 적어 주세요]",
            MaterialItem.JOB_LINK, "[직무 연결: 지원 직무와 이어지는 점을 적어 주세요]",
            MaterialItem.REASON, "[지원 이유: 이 회사를 고른 이유를 적어 주세요]"));
    // AI 가 글이 목표보다 짧을 때 모자란 내용 자리에 넣는 빈칸으로, 문구가 매번 달라 AI 의 _MORE_BLANK 와 같은 모양으로 찾는다
    private static final Pattern MORE_BLANK = Pattern.compile("\\[더 쓸 거리: [^\\[\\]\\n]{1,60}]");

    private DraftText() {
    }

    // 사용자가 채울 빈칸 문구는 빼고, AI(파이썬 len)처럼 문자 단위로 세어 이모지도 한 글자로 친다
    static int countableLength(String text) {

        String counted = MORE_BLANK.matcher(text).replaceAll("");
        for (String blank : BLANKS.values()) {
            counted = counted.replace(blank, "");
        }
        return counted.codePointCount(0, counted.length());

    }

    static List<MaterialItem> blanksIn(String text) {

        return BLANKS.entrySet().stream()
                .filter(blank -> text.contains(blank.getValue()))
                .map(Map.Entry::getKey)
                .toList();

    }
}
