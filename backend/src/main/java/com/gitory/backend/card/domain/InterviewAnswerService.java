package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.AnswerKind;
import com.gitory.backend.card.domain.interview.AnswerSource;
import com.gitory.backend.card.domain.interview.InterviewAnswerReviewProperties;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.TurnOutcome;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 되묻기 답을 가공 없이 질문한 칸의 글로 저장하고 그 칸만 바꾼 INTERVIEW 새 버전을 만들며, 넘기기는 칸을 바꾸지 않고 질문만 넘김으로 남긴다
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
    private final InterviewAnswerReviewProperties review;
    private final UserRateLimiter rateLimiter;

    /**
     * 카드 행을 잠근 뒤 질문 번호, 초안을 만드는 중인지, 이미 한 답, 확정 여부, 답의 모양 순서로 확인하고, 이 순서가 응답 코드의 우선순위다
     * 답 글과 출처는 모양이 틀린 값도 여기까지 들어오므로 카드에 대한 확인을 끝낸 뒤에야 거절한다
     */
    @Transactional
    public CardDetail answer(UUID publicId, Long userId, int turnNo, String kind, String text, String source) {

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
        // 넘기기는 LLM 을 부르지 않으므로 답 평가 호출 한도도 세지 않는다
        if (kindOf(kind) == AnswerKind.SKIP) {
            turn.skip();

            return details.load(publicId, userId);
        }

        AnswerSource answerSource = checkedAnswer(text, source);
        // 답을 저장한 뒤의 평가가 LLM 을 부르지만, 거절된 답이 저장돼 남지 않도록 저장 전에 센다
        if (review.enabled()) {
            rateLimiter.acquire(RateLimitedAction.INTERVIEW_ANSWER, userId, 1);
        }

        StarField slot = turn.getStarSlot();
        short previous = card.getCurrentVersion();
        CardVersion next = writer.startNext(card, VersionSource.INTERVIEW);

        // 답한 칸은 옛 글의 근거를 옮기지 않고 답으로 바꾸므로 나머지 세 칸만 복사한다
        for (StarField other : StarField.values()) {
            if (other != slot) {
                writer.copySlot(card.getId(), previous, other, next.getVersionNo());
            }
        }

        statements.save(CardStatement.answered(card.getId(), next.getVersionNo(), slot, text,
                answerSource.evidenceType(), turn.getId()));
        turn.answer(text, answerSource);

        return details.load(publicId, userId);

    }

    private InterviewTurn turnOf(Card card, int turnNo) {

        // 질문 번호는 SMALLINT 라, 그 범위 밖의 번호를 short 로 바꾸면 다른 질문을 가리키게 된다
        if (turnNo < 1 || turnNo > Short.MAX_VALUE) {
            throw new InterviewTurnNotFoundException();
        }

        return turns.findByCardIdAndSeq(card.getId(), (short) turnNo)
                .orElseThrow(InterviewTurnNotFoundException::new);

    }

    // 넘긴 질문에는 넘기기만, 답한 질문에는 같은 글·출처의 글 답만 같은 요청이고, 모르는 kind 는 어느 쪽과도 같지 않다
    private static boolean sameRequest(InterviewTurn turn, String kind, String text, String source) {

        if (turn.getOutcome() == TurnOutcome.SKIPPED) {
            return AnswerKind.SKIP.name().equals(kind);
        }

        return turn.isAnswered() && (kind == null || AnswerKind.TEXT.name().equals(kind))
                && Objects.equals(turn.getAnswerText(), text)
                && turn.getAnswerSource().evidenceType().name().equals(source);

    }

    // FE 가 아직 kind 를 보내지 않으므로 없으면 글 답으로 보고, 측정 답(MEASURED·NOT_MEASURED)은 아직 받지 않는다
    private static AnswerKind kindOf(String kind) {

        if (kind == null) {
            return AnswerKind.TEXT;
        }

        return Arrays.stream(AnswerKind.values())
                .filter(known -> known.name().equals(kind))
                .findFirst()
                .orElseThrow(() -> new InvalidCardInputException("답의 종류는 TEXT 또는 SKIP 만 보낼 수 있습니다."));

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
