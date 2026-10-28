package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 자소서 문항과 최근 확정 카드 요약으로 AI 카드 고르기를 부르고, 받은 배정을 문항에 저장하는 일을 돌려준다 */
@Component
@RequiredArgsConstructor
class CoverLetterAssignStep {

    // AI 자소서 계약이 받는 카드 수의 상한이라, 넘으면 최근에 확정한 카드부터 보낸다
    private static final int MAX_CARDS = 100;

    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final CoverLetterAssignmentPort assignment;

    Consumer<CoverLetter> run(CoverLetter claimed) {

        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(claimed.getId());
        List<CardMaterial> cards = cardMaterials.read(claimed.getUserId(), MAX_CARDS);
        CoverLetterAssignment result = assignment.assign(requestOf(claimed, loaded, cards));
        return letter -> save(letter, result);

    }

    private void save(CoverLetter letter, CoverLetterAssignment result) {

        Map<Integer, CoverLetterQuestion> bySeq = questions.findByCoverLetterIdOrderBySeq(letter.getId()).stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), Function.identity()));
        for (CoverLetterAssignment.Question picked : result.assignments()) {
            bySeq.get(picked.questionId()).assign(
                    picked.theme() == null ? null : QuestionTheme.valueOf(picked.theme()),
                    picked.primaryCardId(), picked.supportingCardId(), AssignedBy.valueOf(picked.decidedBy()),
                    picked.reason(),
                    picked.considered().stream().map(card -> new ConsideredCard(card.cardId(), card.note())).toList());
        }
        letter.assigned(result.steps().stream()
                .map(step -> new AssignStep(step.seq(), step.actor(), step.kind(), step.questionId(), step.cardId(),
                        step.reason()))
                .toList());

    }

    // 문항 번호(1부터)를 AI 의 문항 id 로 써, AI 가 돌려준 과정의 문항을 번호로 바로 가리킨다
    private static CoverLetterAssignmentRequest requestOf(CoverLetter letter, List<CoverLetterQuestion> loaded,
                                                          List<CardMaterial> cards) {

        return new CoverLetterAssignmentRequest(letter.getId(), letter.getJobRole(), letter.getMemo(),
                loaded.stream()
                        .map(question -> new CoverLetterAssignmentRequest.Question(question.getSeq(), question.getText(),
                                question.getCharLimit()))
                        .toList(),
                cards.stream()
                        .map(card -> new CoverLetterAssignmentRequest.Card(card.id(), card.title(), card.kind().name(),
                                card.sourceName(), card.period(), CoverLetterCardSummary.of(card)))
                        .toList());

    }
}
