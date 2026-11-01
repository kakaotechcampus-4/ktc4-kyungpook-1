package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.MatchingCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 전체 추천을 계산할 때의 입력(확정 카드 글·인적사항 저장 시각)을 줄인 값으로, 새 공고는 넣지 않아 공고가 들어와도 바뀌지 않는다 */
final class InputFingerprint {

    private InputFingerprint() {
    }

    // TODO(#435): 관심 직무를 인적사항에 저장하면 지문과 1단계 거르기에 같이 넣는다
    static String of(List<MatchingCard> cards, Instant profileSavedAt) {

        String content = cards.stream()
                .sorted(Comparator.comparing(MatchingCard::id))
                .map(card -> card.id() + ":" + CardContentHash.of(card))
                .collect(Collectors.joining(",")) + "|" + Objects.toString(profileSavedAt, "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }

    }
}
