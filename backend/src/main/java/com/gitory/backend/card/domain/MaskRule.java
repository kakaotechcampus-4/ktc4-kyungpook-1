package com.gitory.backend.card.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record MaskRule(String from, String to) {

    /**
     * 화면의 가리기(StarBlock.tsx 의 applyMask)와 같은 결과를 내야 한다
     * 같은 from 은 앞 규칙만 쓰고, 긴 from 을 먼저 찾아 원문 기준으로 한 번에 바꾸므로 한 규칙의 결과를 다른 규칙이 다시 가리지 않는다
     */
    public static String apply(String text, List<MaskRule> rules) {

        Map<String, String> byFrom = new LinkedHashMap<>();
        for (MaskRule rule : rules) {
            if (rule.from() != null && !rule.from().isEmpty()) {
                byFrom.putIfAbsent(rule.from(), rule.to());
            }
        }
        if (byFrom.isEmpty()) {
            return text;
        }
        String alternatives = byFrom.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow();
        return Pattern.compile(alternatives).matcher(text).replaceAll(match -> {
            String to = byFrom.get(match.group());
            return Matcher.quoteReplacement(to == null ? match.group() : to);
        });

    }
}
