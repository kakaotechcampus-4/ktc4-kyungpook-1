package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardLabel;
import com.gitory.backend.card.domain.CardLabelReader;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.coverletter.infra.CardMaterialAnswerRepository;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 만들기·조회·카드 바꾸기·다시 시도가 같은 자소서 화면 값을 만들도록, 카드의 공개 id·제목과 재료를 한 곳에서 채운다
 * 지운 카드는 없는 카드로 보여, 문항이 가리키고 있어도 화면에는 비어 있다
 */
@Component
@RequiredArgsConstructor
class CoverLetterViewFactory {

    private static final Map<StarField, MaterialItem> SLOT_ITEMS = Map.of(StarField.S, MaterialItem.SITUATION,
            StarField.A, MaterialItem.ACTION, StarField.R, MaterialItem.RESULT);

    private final CoverLetterQuestionRepository questions;
    private final CardMaterialAnswerRepository answers;
    private final CardLabelReader cardLabels;
    private final CardMaterialReader cardMaterials;

    CoverLetterView of(CoverLetter letter) {

        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        Set<Long> primaryIds = loaded.stream()
                .map(CoverLetterQuestion::getPrimaryCardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CardLabel> labels = cardLabels.liveLabels(cardIdsOf(letter, loaded));
        Map<Long, Set<StarField>> slots = cardMaterials.filledSlots(primaryIds);
        Map<Long, Set<MaterialItem>> remembered = answers.findByCardIdIn(primaryIds).stream()
                .collect(Collectors.groupingBy(CardMaterialAnswer::getCardId,
                        Collectors.mapping(CardMaterialAnswer::getItem, Collectors.toSet())));
        Map<Integer, UUID> questionIds = loaded.stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), CoverLetterQuestion::getPublicId));

        return new CoverLetterView(letter.getPublicId(), letter.getStage(), letter.getFailedStep(),
                letter.getErrorCode(), retryable(letter), letter.isRunning(), letter.getJobRole(), letter.getMemo(),
                letter.getCreatedAt(), letter.getUpdatedAt(),
                loaded.stream().map(question -> question(question, labels, slots, remembered)).toList(),
                letter.getAssignSteps().stream().map(step -> step(step, questionIds, labels)).toList(),
                letter.getDraftSteps().stream().map(step -> writingStep(step, questionIds)).toList());

    }

    private static Boolean retryable(CoverLetter letter) {

        return letter.getErrorCode() == null ? null : CoverLetterError.AI_UNAVAILABLE.name().equals(letter.getErrorCode());

    }

    private static Collection<Long> cardIdsOf(CoverLetter letter, List<CoverLetterQuestion> loaded) {

        Set<Long> ids = new HashSet<>();
        for (CoverLetterQuestion question : loaded) {
            ids.add(question.getPrimaryCardId());
            ids.add(question.getSupportingCardId());
            question.getConsidered().forEach(card -> ids.add(card.cardId()));
        }
        letter.getAssignSteps().forEach(step -> ids.add(step.cardId()));
        ids.remove(null);
        return ids;

    }

    private static CoverLetterView.Question question(CoverLetterQuestion question, Map<Long, CardLabel> labels,
                                                     Map<Long, Set<StarField>> slots,
                                                     Map<Long, Set<MaterialItem>> remembered) {

        List<CoverLetterView.Considered> considered = question.getConsidered().stream()
                .filter(card -> labels.containsKey(card.cardId()))
                .map(card -> new CoverLetterView.Considered(labels.get(card.cardId()).publicId(),
                        labels.get(card.cardId()).title(), card.note()))
                .toList();

        return new CoverLetterView.Question(question.getPublicId(), question.getSeq(), question.getText(),
                question.getCharLimit(), question.getTheme(), cardRef(labelOf(labels, question.getPrimaryCardId())),
                cardRef(labelOf(labels, question.getSupportingCardId())), question.getAssignedBy(),
                question.getPickReason(), considered, materials(question.getPrimaryCardId(), labels, slots, remembered),
                asks(question), draft(question));

    }

    private static CoverLetterView.QuestionDraft draft(CoverLetterQuestion question) {

        if (question.getAiText() == null) {
            return null;
        }

        return new CoverLetterView.QuestionDraft(question.getDraftText(), question.getAiText(), question.isEdited(),
                question.getCharCount(), question.getOverBy(), question.getSentences(), question.getMarks(),
                question.getBlanks(), question.getDraftSource());

    }

    private static List<CoverLetterView.Ask> asks(CoverLetterQuestion question) {

        Map<MaterialItem, MaterialAnswer> answers = question.getAnswers().stream()
                .collect(Collectors.toMap(MaterialAnswer::item, Function.identity()));
        return question.getAsks().stream().map(ask -> ask(ask, answers.get(ask.item()))).toList();

    }

    private static CoverLetterView.Ask ask(MaterialAsk ask, MaterialAnswer answer) {

        boolean skipped = answer != null && answer.skipped();
        CoverLetterView.Answer written = answer == null || skipped ? null
                : new CoverLetterView.Answer(answer.text(), answer.reused());
        return new CoverLetterView.Ask(ask.item(), ask.question(), ask.templates(), ask.required(), written, skipped);

    }

    // 중심 카드의 상황·행동·결과 문장과 같은 카드로 전에 받은 답에 이미 있는 항목이다
    private static List<MaterialItem> materials(Long primaryCardId, Map<Long, CardLabel> labels,
                                                Map<Long, Set<StarField>> slots,
                                                Map<Long, Set<MaterialItem>> remembered) {

        if (primaryCardId == null || !labels.containsKey(primaryCardId)) {
            return List.of();
        }

        Set<MaterialItem> items = EnumSet.noneOf(MaterialItem.class);
        slots.getOrDefault(primaryCardId, Set.of()).stream()
                .map(SLOT_ITEMS::get)
                .filter(Objects::nonNull)
                .forEach(items::add);
        items.addAll(remembered.getOrDefault(primaryCardId, Set.of()));
        return List.copyOf(items);

    }

    private static CardLabel labelOf(Map<Long, CardLabel> labels, Long cardId) {

        return cardId == null ? null : labels.get(cardId);

    }

    private static CoverLetterView.CardRef cardRef(CardLabel label) {

        return label == null ? null : new CoverLetterView.CardRef(label.publicId(), label.title(), label.kind());

    }

    private static CoverLetterView.Step step(AssignStep step, Map<Integer, UUID> questionIds,
                                             Map<Long, CardLabel> labels) {

        CardLabel card = labelOf(labels, step.cardId());
        return new CoverLetterView.Step(step.seq(), step.actor(), step.kind(),
                step.questionSeq() == null ? null : questionIds.get(step.questionSeq()),
                card == null ? null : card.publicId(), step.reason());

    }

    private static CoverLetterView.WritingStep writingStep(DraftStep step, Map<Integer, UUID> questionIds) {

        return new CoverLetterView.WritingStep(step.seq(), step.actor(), step.kind(),
                step.questionSeq() == null ? null : questionIds.get(step.questionSeq()), step.code(), step.value(),
                step.sentenceSeq(), step.action(), step.reason());

    }
}
