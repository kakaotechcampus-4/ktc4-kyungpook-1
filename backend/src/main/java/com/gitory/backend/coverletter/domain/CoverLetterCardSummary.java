package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialStatement;
import com.gitory.backend.card.domain.StarField;

import java.util.List;

/**
 * 카드 고르기 AI 에 카드마다 보낼 300자 요약으로, 측정 세트(measure.py 의 summary)와 같은 방식이어야 잰 숫자가 운영과 같다
 * 행동·결과 문장을 잇고, 둘 다 없으면 있는 문장 전부, 문장이 하나도 없으면 제목이다
 */
final class CoverLetterCardSummary {

    private static final int MAX_LENGTH = 300;

    private CoverLetterCardSummary() {
    }

    static String of(CardMaterial card) {

        List<String> actionAndResult = card.statements().stream()
                .filter(statement -> statement.slot() == StarField.A || statement.slot() == StarField.R)
                .map(CardMaterialStatement::text)
                .toList();
        List<String> picked = actionAndResult.isEmpty()
                ? card.statements().stream().map(CardMaterialStatement::text).toList()
                : actionAndResult;
        String summary = picked.isEmpty() ? card.title() : String.join(" ", picked);

        return summary.length() <= MAX_LENGTH ? summary : summary.substring(0, MAX_LENGTH);

    }
}
