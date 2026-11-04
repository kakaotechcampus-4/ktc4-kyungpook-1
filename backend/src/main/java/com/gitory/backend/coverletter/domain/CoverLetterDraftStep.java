package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsListener;
import com.gitory.backend.agent.port.CoverLetterDraftsPort;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 문항마다 배정된 카드 전문과 받은 답을 AI 초안 쓰기에 보내고, 받은 초안과 과정을 저장하는 일을 돌려준다
 * 꼭 있어야 하는데 답을 받지 못한 항목은 AI 가 지어내지 않고 빈칸으로 두도록 따로 알린다
 */
@Component
@RequiredArgsConstructor
class CoverLetterDraftStep {

    // 경험 자체에 대한 답이라 중심 카드의 재료로 세고, 직무 연결·지원 이유는 카드와 상관없는 답이다
    private static final Set<MaterialItem> CARD_ITEMS = EnumSet.of(MaterialItem.SITUATION, MaterialItem.ACTION,
            MaterialItem.RESULT, MaterialItem.LEARNING, MaterialItem.TRIGGER);

    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final CoverLetterDraftsPort drafts;

    /** AI 가 글을 쓰거나 단계를 거칠 때마다 live 에 남겨, 초안을 쓰는 동안에도 조회가 글과 과정을 보이게 한다 */
    Consumer<CoverLetter> run(CoverLetter claimed, CoverLetterLive live) {

        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(claimed.getId());
        Map<Long, CardMaterial> cards = cardMaterials.readOf(CoverLetterCardDetails.cardIdsOf(loaded)).stream()
                .collect(Collectors.toMap(CardMaterial::id, Function.identity()));

        CoverLetterDrafts result = drafts.write(new CoverLetterDraftsRequest(claimed.getId(), claimed.getJobRole(),
                claimed.memoForWriting(), claimed.strengthCodes(), loaded.stream().map(question -> requestOf(question, cards)).toList(),
                CoverLetterPosting.inputOf(claimed)), listenerOf(live));
        return letter -> save(letter, result);

    }

    private static CoverLetterDraftsListener listenerOf(CoverLetterLive live) {

        return new CoverLetterDraftsListener() {

            @Override
            public void text(int questionId, int round, String text) {

                live.write(questionId, round, text);

            }

            @Override
            public void step(CoverLetterDrafts.Step step) {

                live.draftStep(stepOf(step));

            }
        };

    }

    private void save(CoverLetter letter, CoverLetterDrafts result) {

        Map<Integer, CoverLetterQuestion> bySeq = questions.findByCoverLetterIdOrderBySeq(letter.getId()).stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), Function.identity()));
        for (CoverLetterDrafts.Draft draft : result.drafts()) {
            bySeq.get(draft.questionId()).drafted(draft.text(),
                    draft.sentences().stream().map(sentence -> new DraftSentence(sentence.seq(), sentence.text())).toList(),
                    draft.charCount(), draft.overBy(),
                    draft.marks().stream().map(mark -> new DraftMark(mark.sentenceSeq(), mark.code(), mark.value())).toList(),
                    draft.blanks().stream().map(MaterialItem::valueOf).toList(), DraftSource.valueOf(draft.source()));
        }
        letter.drafted(result.steps().stream().map(CoverLetterDraftStep::stepOf).toList());

    }

    private static DraftStep stepOf(CoverLetterDrafts.Step step) {

        return new DraftStep(step.seq(), step.actor(), step.kind(), step.questionId(), step.code(), step.value(),
                step.sentenceSeq(), step.sentence(), step.action(), step.reason());

    }

    private static CoverLetterDraftsRequest.Question requestOf(CoverLetterQuestion question,
                                                               Map<Long, CardMaterial> cards) {

        CardMaterial primary = CoverLetterCardDetails.primaryOf(question, cards);
        Set<MaterialItem> answered = question.getAnswers().stream()
                .filter(answer -> !answer.skipped())
                .map(MaterialAnswer::item)
                .collect(Collectors.toSet());

        return new CoverLetterDraftsRequest.Question(question.getSeq(), question.getText(), question.getCharLimit(),
                question.getTheme() == null ? null : question.getTheme().name(),
                CoverLetterCardDetails.detail(primary),
                CoverLetterCardDetails.detail(CoverLetterCardDetails.supportingOf(question, cards)),
                answersOf(question, primary),
                question.getAsks().stream()
                        .filter(MaterialAsk::required)
                        .map(MaterialAsk::item)
                        .filter(item -> !answered.contains(item))
                        .map(MaterialItem::name)
                        .toList());

    }

    static List<CoverLetterQuestionsRequest.KnownAnswer> answersOf(CoverLetterQuestion question,
                                                                   CardMaterial primary) {

        return question.getAnswers().stream()
                .filter(answer -> !answer.skipped())
                .map(answer -> new CoverLetterQuestionsRequest.KnownAnswer(answer.item().name(), answer.text(),
                        primary != null && CARD_ITEMS.contains(answer.item()) ? primary.id() : null))
                .toList();

    }
}
