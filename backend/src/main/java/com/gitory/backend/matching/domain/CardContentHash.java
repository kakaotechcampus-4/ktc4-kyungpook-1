package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** AI 에 보내는 카드 글(제목·STAR 네 칸·기술 이름표)이 같으면 같은 값이 나와, 판단을 다시 쓸지 정하는 데 쓴다 */
final class CardContentHash {

    // 칸 글에 들어갈 일이 없는 문자로 칸을 나눠, 앞 칸 끝과 뒤 칸 처음이 붙어 다른 글과 같은 값이 되지 않게 한다
    private static final String SEPARATOR = "\u001F";

    private CardContentHash() {
    }

    static String of(MatchingCard card) {

        String techs = card.techs().stream().map(CardTech::name).sorted().collect(Collectors.joining(","));
        String content = Stream.of(card.title(), card.situation(), card.task(), card.action(), card.result(), techs)
                .map(text -> Objects.requireNonNullElse(text, ""))
                .collect(Collectors.joining(SEPARATOR));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }

    }
}
