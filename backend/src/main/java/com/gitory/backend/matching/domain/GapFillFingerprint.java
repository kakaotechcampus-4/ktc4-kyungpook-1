package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.MatchingCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 모자란 조건 채우기의 입력(못 채운 조건·확정 카드 글·작성 중 카드 글·새 후보·저장소와 분석 여부)을 줄인 값으로, 같으면 저장한 결과를 다시 쓴다 */
final class GapFillFingerprint {

    private GapFillFingerprint() {
    }

    static String of(List<UUID> conditionIds, Map<UUID, MatchingCard> confirmed, Map<UUID, MatchingCard> drafts,
                     List<UUID> candidateIds, Map<UUID, Boolean> analyzedByRepo) {

        String content = Stream.of(
                        conditionIds.stream().map(UUID::toString).collect(Collectors.joining(",")),
                        cards(confirmed),
                        cards(drafts),
                        candidateIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(",")),
                        analyzedByRepo.entrySet().stream()
                                .map(repo -> repo.getKey() + ":" + repo.getValue())
                                .sorted()
                                .collect(Collectors.joining(",")))
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }

    }

    private static String cards(Map<UUID, MatchingCard> cards) {

        return cards.entrySet().stream()
                .map(card -> card.getKey() + ":" + CardContentHash.of(card.getValue()))
                .sorted()
                .collect(Collectors.joining(","));

    }
}
