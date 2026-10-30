package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.CoverLetterQuestions;
import com.gitory.backend.agent.port.CoverLetterQuestionsPort;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 문항마다 배정된 카드 전문과 중심 카드로 전에 받은 답을 AI 질문 만들기에 보내고, 받은 질문을 문항에 저장하는 일을 돌려준다
 * 지난 답은 AI 가 다시 묻지 않게 함께 보내고, 사용자가 보고 고칠 수 있게 질문 없는 줄로 문항 질문 끝에 붙인다
 */
@Component
@RequiredArgsConstructor
class CoverLetterAskStep {

    // AI 자소서 계약이 카드마다 받는 문장 수의 상한이다
    private static final int MAX_STATEMENTS = 20;

    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final CardMaterialAnswerRepository rememberedAnswers;
    private final CoverLetterQuestionsPort questionsPort;

    Consumer<CoverLetter> run(CoverLetter claimed) {

        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(claimed.getId());
        Map<Long, CardMaterial> cards = cardMaterials.readOf(cardIdsOf(loaded)).stream()
                .collect(Collectors.toMap(CardMaterial::id, Function.identity()));
        Map<Long, List<CardMaterialAnswer>> remembered = rememberedAnswers.findByCardIdIn(cards.keySet()).stream()
                .sorted(Comparator.comparing(CardMaterialAnswer::getItem))
                .collect(Collectors.groupingBy(CardMaterialAnswer::getCardId));

        Map<Integer, List<CardMaterialAnswer>> known = new HashMap<>();
        List<CoverLetterQuestionsRequest.Question> sent = new ArrayList<>();
        for (CoverLetterQuestion question : loaded) {
            CardMaterial primary = question.getPrimaryCardId() == null ? null : cards.get(question.getPrimaryCardId());
            // AI 는 중심 카드 없이 보조 카드만 받지 않아, 중심 카드를 지웠으면 보조 카드도 보내지 않는다
            CardMaterial supporting = primary == null || question.getSupportingCardId() == null ? null
                    : cards.get(question.getSupportingCardId());
            List<CardMaterialAnswer> answers = primary == null ? List.of()
                    : remembered.getOrDefault(primary.id(), List.of());
            known.put((int) question.getSeq(), answers);
            sent.add(new CoverLetterQuestionsRequest.Question(question.getSeq(), question.getText(),
                    question.getCharLimit(), question.getTheme() == null ? null : question.getTheme().name(),
                    detail(primary), detail(supporting),
                    answers.stream()
                            .map(answer -> new CoverLetterQuestionsRequest.KnownAnswer(answer.getItem().name(),
                                    answer.getText(), answer.getCardId()))
                            .toList()));
        }

        CoverLetterQuestions result = questionsPort.ask(new CoverLetterQuestionsRequest(claimed.getId(),
                claimed.getJobRole(), claimed.getMemo(), sent));
        return letter -> save(letter, result, known);

    }

    private void save(CoverLetter letter, CoverLetterQuestions result, Map<Integer, List<CardMaterialAnswer>> known) {

        Map<Integer, CoverLetterQuestion> bySeq = questions.findByCoverLetterIdOrderBySeq(letter.getId()).stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), Function.identity()));
        for (CoverLetterQuestions.Question asked : result.questions()) {
            List<CardMaterialAnswer> reused = known.getOrDefault(asked.questionId(), List.of());
            bySeq.get(asked.questionId()).asked(asksOf(asked, reused), reused.stream()
                    .map(answer -> new MaterialAnswer(answer.getItem(), answer.getText(), false, true))
                    .toList());
        }
        letter.asked();

    }

    // 답은 항목으로 질문을 찾아 붙으므로, AI 가 묻지 않은 지난 답 항목도 질문 없는 줄로 남긴다
    private static List<MaterialAsk> asksOf(CoverLetterQuestions.Question asked, List<CardMaterialAnswer> reused) {

        List<MaterialAsk> fromAi = asked.asks().stream()
                .map(ask -> new MaterialAsk(MaterialItem.valueOf(ask.item()), ask.question(),
                        asked.requiredMissing().contains(ask.item())))
                .toList();
        Set<MaterialItem> askedItems = fromAi.stream().map(MaterialAsk::item).collect(Collectors.toSet());

        return Stream.concat(fromAi.stream(), reused.stream()
                        .map(CardMaterialAnswer::getItem)
                        .filter(item -> !askedItems.contains(item))
                        .map(item -> new MaterialAsk(item, null, false)))
                .toList();

    }

    private static List<Long> cardIdsOf(List<CoverLetterQuestion> loaded) {

        return loaded.stream()
                .flatMap(question -> Stream.of(question.getPrimaryCardId(), question.getSupportingCardId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

    }

    private static CoverLetterQuestionsRequest.CardDetail detail(CardMaterial card) {

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
