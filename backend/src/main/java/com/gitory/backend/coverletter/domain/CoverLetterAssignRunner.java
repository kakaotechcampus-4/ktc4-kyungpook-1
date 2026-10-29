package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentPort;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카드 고르기를 기다리는 자소서를 하나 집어 AI 카드 고르기를 부르고, 결과를 문항에 저장하거나 실패로 끝낸다
 * 집은 시각을 커밋한 뒤에 AI 를 불러, 응답을 기다리는 동안 DB 커넥션과 잠금을 붙잡지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverLetterAssignRunner {

    // AI 자소서 계약이 받는 카드 수의 상한이라, 넘으면 최근에 확정한 카드부터 보낸다
    private static final int MAX_CARDS = 100;
    // AI 응답 대기 한도(180초)보다 넉넉해, 응답을 기다리는 단계를 멈춘 것으로 보지 않는다
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final CoverLetterAssignmentPort assignment;
    private final TransactionTemplate transaction;

    /** 집은 자소서는 집은 시각을 커밋한 뒤에 돌려주고, 기다리는 자소서가 없으면 빈 값을 돌려준다 */
    public Optional<CoverLetter> claimNext() {

        return transaction.execute(status -> letters.findNextWaitingForUpdate().map(letter -> {
            letter.claim();
            return letter;
        }));

    }

    public void run(CoverLetter claimed) {

        AiCallContext.runForCoverLetter(claimed.getId(), () -> assign(claimed));

    }

    /** 집은 지 5분이 지나도 끝나지 않은 단계는 다시 시도할 수 있는 실패로 끝낸다 */
    public void failStuck() {

        Instant before = Instant.now().minus(STUCK_AFTER);
        transaction.executeWithoutResult(status -> letters.findStuckForUpdate(before).forEach(letter -> {
            log.warn("자소서 {} 의 {} 단계가 {} 부터 끝나지 않아 실패로 끝낸다", letter.getId(), letter.getStage(),
                    letter.getRunningSince());
            letter.fail(CoverLetterError.AI_UNAVAILABLE);
        }));

    }

    /**
     * 서버가 꺼질 때 돌던 단계를 다시 줄 세운다
     * 서버가 1대라는 전제다 — 2대면 다른 서버가 돌리는 단계까지 되돌리므로 그때는 하트비트 방식으로 바꾼다
     */
    public void requeueRunning() {

        transaction.executeWithoutResult(status -> letters.findWithLockByRunningSinceIsNotNull()
                .forEach(CoverLetter::requeue));

    }

    private void assign(CoverLetter claimed) {

        try {
            List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(claimed.getId());
            List<CardMaterial> cards = cardMaterials.read(claimed.getUserId(), MAX_CARDS);
            CoverLetterAssignment result = assignment.assign(requestOf(claimed, loaded, cards));
            transaction.executeWithoutResult(status -> save(claimed, result));
        } catch (AgentUnavailableException failure) {
            CoverLetterError error = failure.isRetryable() ? CoverLetterError.AI_UNAVAILABLE : CoverLetterError.INTERNAL_ERROR;
            log.warn("자소서 {} 의 카드 고르기를 {} 로 끝낸다", claimed.getId(), error, failure);
            transaction.executeWithoutResult(status -> fail(claimed, error));
        } catch (RuntimeException failure) {
            log.warn("자소서 {} 의 카드 고르기를 {} 로 끝낸다", claimed.getId(), CoverLetterError.INTERNAL_ERROR, failure);
            transaction.executeWithoutResult(status -> fail(claimed, CoverLetterError.INTERNAL_ERROR));
        }

    }

    private void save(CoverLetter claimed, CoverLetterAssignment result) {

        Optional<CoverLetter> current = stillClaimed(claimed);
        if (current.isEmpty()) {
            return;
        }

        Map<Integer, CoverLetterQuestion> bySeq = questions.findByCoverLetterIdOrderBySeq(claimed.getId()).stream()
                .collect(Collectors.toMap(question -> (int) question.getSeq(), Function.identity()));
        for (CoverLetterAssignment.Question picked : result.assignments()) {
            bySeq.get(picked.questionId()).assign(
                    picked.theme() == null ? null : QuestionTheme.valueOf(picked.theme()),
                    picked.primaryCardId(), picked.supportingCardId(), AssignedBy.valueOf(picked.decidedBy()),
                    picked.reason(),
                    picked.considered().stream().map(card -> new ConsideredCard(card.cardId(), card.note())).toList());
        }
        current.get().assigned(result.steps().stream()
                .map(step -> new AssignStep(step.seq(), step.actor(), step.kind(), step.questionId(), step.cardId(),
                        step.reason()))
                .toList());

    }

    private void fail(CoverLetter claimed, CoverLetterError error) {

        stillClaimed(claimed).ifPresent(letter -> letter.fail(error));

    }

    // 기다리는 사이 멈춘 단계로 실패 처리되었거나 다시 시도로 새로 집혔으면 집은 시각이 달라져, 이번 결과는 버린다
    private Optional<CoverLetter> stillClaimed(CoverLetter claimed) {

        return letters.findWithLockById(claimed.getId())
                .filter(letter -> claimed.getRunningSince().equals(letter.getRunningSince()));

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
