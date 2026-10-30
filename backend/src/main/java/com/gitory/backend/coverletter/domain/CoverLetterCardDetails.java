package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
import com.gitory.backend.card.domain.CardMaterial;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 문항에 배정된 카드를 AI 질문 만들기·초안 쓰기 요청에 싣는 모양으로 바꾼다
 * AI 는 중심 카드 없이 보조 카드만 받지 않아, 중심 카드를 지웠으면 보조 카드도 싣지 않는다
 */
final class CoverLetterCardDetails {

    // AI 자소서 계약이 카드마다 받는 문장 수의 상한이다
    private static final int MAX_STATEMENTS = 20;

    private CoverLetterCardDetails() {
    }

    static List<Long> cardIdsOf(List<CoverLetterQuestion> loaded) {

        return loaded.stream()
                .flatMap(question -> Stream.of(question.getPrimaryCardId(), question.getSupportingCardId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

    }

    static CardMaterial primaryOf(CoverLetterQuestion question, Map<Long, CardMaterial> cards) {

        return question.getPrimaryCardId() == null ? null : cards.get(question.getPrimaryCardId());

    }

    static CardMaterial supportingOf(CoverLetterQuestion question, Map<Long, CardMaterial> cards) {

        return primaryOf(question, cards) == null || question.getSupportingCardId() == null ? null
                : cards.get(question.getSupportingCardId());

    }

    static CoverLetterQuestionsRequest.CardDetail detail(CardMaterial card) {

        if (card == null) {
            return null;
        }

        return new CoverLetterQuestionsRequest.CardDetail(card.id(), card.title(), card.kind().name(),
                card.sourceName(), card.statements().stream()
                .limit(MAX_STATEMENTS)
                .map(statement -> new CoverLetterQuestionsRequest.Statement(statement.slot().name(), statement.text()))
                .toList());

    }
}
