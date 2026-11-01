package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.AnswerKind;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.AnswerSource;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.TurnOutcome;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 되묻기 답을 가공 없이 질문한 칸의 글로 저장하고 그 칸만 바꾼 INTERVIEW 새 버전을 만들며, 넘기기는 칸을 바꾸지 않고 질문만 넘김으로 남긴다
 * '측정 안 했어요' 는 커밋으로 확인된 R 문장을 지우지 않도록 그 문장이 있으면 칸을 바꾸지 않고 답만 기록한다
 * 이미 정해진 결과와 같은 요청이 다시 오면 응답이 끊긴 뒤의 재시도이므로 확정된 카드여도 아무것도 쓰지 않고 지금 카드를 돌려준다
 */
@Service
@RequiredArgsConstructor
public class InterviewAnswerService {

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final CardVersionWriter writer;
    private final CardStatementRepository statements;
    private final CardDetailService details;
    private final CardVersionReader reader;
    private final CardThemeSuggester themeSuggester;

    /**
     * 카드 행을 잠근 뒤 질문 번호, 초안을 만드는 중인지, 이미 한 답, 확정 여부, 답의 모양 순서로 확인하고, 이 순서가 응답 코드의 우선순위다
     * 답 글과 출처는 모양이 틀린 값도 여기까지 들어오므로 카드에 대한 확인을 끝낸 뒤에야 거절한다
     */
    @Transactional
    public CardDetail answer(UUID publicId, Long userId, int turnNo, String kind, String text, String source,
                             String original) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        InterviewTurn turn = turnOf(card, turnNo);
        generation.requireNotGenerating(card);

        if (turn.isClosed()) {
            if (!sameRequest(turn, kind, text, source)) {
                throw new InterviewTurnAnsweredException();
            }

            return details.load(publicId, userId);
        }

        card.requireDraft();
        AnswerKind answerKind = kindOf(kind);
        // 글 답은 지금 화면이 수치 질문에도 kind 없이 보내므로 막지 않고, 측정 답만 수치 질문인지 본다
        if (answerKind != AnswerKind.TEXT && answerKind != AnswerKind.SKIP
                && turn.answerModeOrText() != AnswerMode.MEASURE) {
            throw new InvalidCardInputException("측정 답은 R 수치 질문에만 보낼 수 있습니다.");
        }

        switch (answerKind) {
            case SKIP -> turn.skip();
            case TEXT -> {
                AnswerSource answerSource = checkedAnswer(text, source);
                String checkedOriginal = checkedOriginal(original);
                replaceSlot(card, turn, text, answerSource);
                turn.answer(text, answerSource, checkedOriginal);
            }
            case MEASURED -> {
                AnswerSource answerSource = checkedAnswer(text, source);
                replaceSlot(card, turn, text, answerSource);
                turn.measure(text, answerSource);
            }
            case NOT_MEASURED -> recordNotMeasured(card, turn, text, source);
        }
        // 되묻기 화면은 칸을 골라 묻기만 해서 '되묻기 끝' 판정이 오지 않으므로, 답으로 네 칸이 확인 필요 없이 다 차면 여기서 확정한다
        themeSuggester.settle(card, reader.read(card.getId(), card.getCurrentVersion()));

        return details.load(publicId, userId);

    }

    // 숫자 없는 문장을 함께 보내도 R 이 커밋으로 확인된 문장이면 그 문장을 지우지 않고, 빈 R·확인 필요 R 일 때만 반영한다
    private void recordNotMeasured(Card card, InterviewTurn turn, String text, String source) {

        if (text == null) {
            turn.notMeasured(null, null);
            return;
        }

        AnswerSource answerSource = checkedAnswer(text, source);
        if (!confirmedByCommit(card, turn.getStarSlot())) {
            replaceSlot(card, turn, text, answerSource);
        }
        turn.notMeasured(text, answerSource);

    }

    private boolean confirmedByCommit(Card card, StarField slot) {

        return statements.findByCardIdAndVersionNoAndStarSlot(card.getId(), card.getCurrentVersion(), slot).stream()
                .anyMatch(statement -> statement.getEvidenceType() == EvidenceType.COMMIT
                        && statement.getConfidence() != Confidence.LOW);

    }

    // 답한 칸은 옛 글의 근거를 옮기지 않고 답으로 바꾸므로 나머지 세 칸만 복사한다
    private void replaceSlot(Card card, InterviewTurn turn, String text, AnswerSource answerSource) {

        StarField slot = turn.getStarSlot();
        short previous = card.getCurrentVersion();
        CardVersion next = writer.startNext(card, VersionSource.INTERVIEW);

        for (StarField other : StarField.values()) {
            if (other != slot) {
                writer.copySlot(card.getId(), previous, other, next.getVersionNo());
            }
        }

        statements.save(CardStatement.answered(card.getId(), next.getVersionNo(), slot, text,
                answerSource.evidenceType(), turn.getId()));

    }

    private static String checkedOriginal(String original) {

        if (original == null || CardText.isBlank(original)) {
            return null;
        }
        CardText.requireNoNul(original, "다듬기 전 답");
        if (original.length() > DraftSaveService.SLOT_MAX_LENGTH) {
            throw new InvalidCardInputException("다듬기 전 답은 " + DraftSaveService.SLOT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        return original;

    }

    private InterviewTurn turnOf(Card card, int turnNo) {

        // 질문 번호는 SMALLINT 라, 그 범위 밖의 번호를 short 로 바꾸면 다른 질문을 가리키게 된다
        if (turnNo < 1 || turnNo > Short.MAX_VALUE) {
            throw new InterviewTurnNotFoundException();
        }

        return turns.findByCardIdAndSeq(card.getId(), (short) turnNo)
                .orElseThrow(InterviewTurnNotFoundException::new);

    }

    // 결과와 같은 종류의 요청에 같은 글·출처가 와야 같은 요청이고, 넘기기는 글을 보지 않으며, 모르는 kind 는 어느 결과와도 같지 않다
    private static boolean sameRequest(InterviewTurn turn, String kind, String text, String source) {

        Optional<AnswerKind> requested = kind == null ? Optional.of(AnswerKind.TEXT) : known(kind);
        if (requested.isEmpty() || outcomeOf(requested.get()) != turn.getOutcome()) {
            return false;
        }

        if (requested.get() == AnswerKind.SKIP) {
            return true;
        }

        if (turn.getAnswerText() == null) {
            return text == null;
        }

        return Objects.equals(turn.getAnswerText(), text)
                && turn.getAnswerSource().evidenceType().name().equals(source);

    }

    private static TurnOutcome outcomeOf(AnswerKind kind) {

        return switch (kind) {
            case TEXT -> TurnOutcome.ANSWERED;
            case MEASURED -> TurnOutcome.MEASURED;
            case NOT_MEASURED -> TurnOutcome.NOT_MEASURED;
            case SKIP -> TurnOutcome.SKIPPED;
        };

    }

    // FE 가 아직 kind 를 보내지 않으므로 없으면 글 답으로 본다
    private static AnswerKind kindOf(String kind) {

        if (kind == null) {
            return AnswerKind.TEXT;
        }

        return known(kind).orElseThrow(() -> new InvalidCardInputException(
                "답의 종류는 TEXT·MEASURED·NOT_MEASURED·SKIP 만 보낼 수 있습니다."));

    }

    private static Optional<AnswerKind> known(String kind) {

        return Arrays.stream(AnswerKind.values()).filter(value -> value.name().equals(kind)).findFirst();

    }

    private static AnswerSource checkedAnswer(String text, String source) {

        AnswerSource answerSource = Arrays.stream(AnswerSource.values())
                .filter(known -> known.evidenceType().name().equals(source))
                .findFirst()
                .orElseThrow(() -> new InvalidCardInputException("답의 출처는 USER_STATED 또는 USER_SELECTED 만 보낼 수 있습니다."));

        if (text == null) {
            throw new InvalidCardInputException("답을 입력해 주세요.");
        }

        CardText.requireNoNul(text, "답");

        if (CardText.isBlank(text)) {
            throw new InvalidCardInputException("답을 입력해 주세요.");
        }

        if (text.length() > DraftSaveService.SLOT_MAX_LENGTH) {
            throw new InvalidCardInputException("답은 " + DraftSaveService.SLOT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        return answerSource;

    }
}
