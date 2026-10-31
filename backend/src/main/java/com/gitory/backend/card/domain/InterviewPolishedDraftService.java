package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewPolishPort;
import com.gitory.backend.agent.port.InterviewPolishRequest;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.InvalidInterviewFieldException;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 경험 카드의 글 답을 AI 가 표현만 다듬은 문장으로 미리 보여 주고, 아무것도 저장하지 않는다
 * 다듬은 문장에 사용자 답에 없는 숫자가 있으면 화면이 강조하도록 서버가 견줘 알리고, AI 가 실패하면 사용자 답을 그대로 돌려준다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPolishedDraftService {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final InterviewPolishPort polisher;
    private final UserRateLimiter rateLimiter;
    private final PlatformTransactionManager transactionManager;

    /**
     * 질문 번호(404), 초안을 만드는 중인 카드·확정된 카드·결과가 정해진 질문(409), 경험 카드의 글 질문이 아님(400), 글의 모양(400)
     * 순서로 확인한다
     */
    public PolishedDraft draft(UUID publicId, Long userId, int turnNo, String text) {

        Prepared prepared = readOnly().execute(status -> prepare(publicId, userId, turnNo, text));

        rateLimiter.acquire(RateLimitedAction.INTERVIEW_ANSWER, userId, 1);
        String answer = text.strip();
        String written;
        try {
            InterviewPolishRequest request = new InterviewPolishRequest(prepared.cardId(), prepared.slot(),
                    prepared.question(), answer);
            written = AiCallContext.callForCard(prepared.cardId(), () -> polisher.polish(request));
        } catch (AgentUnavailableException failure) {
            log.warn("카드 {} 의 답을 AI 가 다듬지 못해 사용자 답을 그대로 보여 준다", prepared.cardId(), failure);
            return PolishedDraft.unpolished(answer);
        }

        // 칸에 저장할 수 없는 길이면 다듬은 문장을 쓸 수 없다
        if (written.length() > DraftSaveService.SLOT_MAX_LENGTH) {
            log.warn("카드 {} 의 다듬은 문장이 칸 길이를 넘어 사용자 답을 그대로 보여 준다", prepared.cardId());
            return PolishedDraft.unpolished(answer);
        }

        return new PolishedDraft(written, true, inventedNumbers(written, answer));

    }

    private TransactionTemplate readOnly() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);

        return template;

    }

    private Prepared prepare(UUID publicId, Long userId, int turnNo, String text) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.EDIT_CARD);
        // 질문 번호는 SMALLINT 라, 그 범위 밖의 번호를 short 로 바꾸면 다른 질문을 가리키게 된다
        if (turnNo < 1 || turnNo > Short.MAX_VALUE) {
            throw new InterviewTurnNotFoundException();
        }
        InterviewTurn turn = turns.findByCardIdAndSeq(card.getId(), (short) turnNo)
                .orElseThrow(InterviewTurnNotFoundException::new);

        generation.requireNotGenerating(card);
        card.requireDraft();
        if (turn.isClosed()) {
            throw new InterviewTurnAnsweredException();
        }
        // 기술 카드의 답은 커밋 근거를 보충할 뿐이라 다듬지 않고, 수치 질문은 '재 봤어요' 미리 보기를 쓴다
        if (card.getCardType() != CardKind.QUALITATIVE || turn.answerModeOrText() != AnswerMode.TEXT) {
            throw new InvalidInterviewFieldException();
        }
        checkedText(text);

        return new Prepared(card.getId(), InterviewStarSlot.valueOf(turn.getStarSlot().name()), turn.getQuestionText());

    }

    private static void checkedText(String text) {

        if (text == null || CardText.isBlank(text)) {
            throw new InvalidCardInputException("답을 입력해 주세요.");
        }

        CardText.requireNoNul(text, "답");

        if (text.length() > DraftSaveService.SLOT_MAX_LENGTH) {
            throw new InvalidCardInputException("답은 " + DraftSaveService.SLOT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

    }

    // 1,000 과 1000 을 같은 수로 보도록 쉼표를 지우고 견주며, 다듬은 문장에 나온 순서대로 한 번씩 돌려준다
    private static List<String> inventedNumbers(String written, String answer) {

        Set<String> answered = new HashSet<>();
        Matcher inAnswer = NUMBER.matcher(answer);
        while (inAnswer.find()) {
            answered.add(inAnswer.group().replace(",", ""));
        }

        List<String> invented = new ArrayList<>();
        Matcher inWritten = NUMBER.matcher(written);
        while (inWritten.find()) {
            String number = inWritten.group();
            if (!answered.contains(number.replace(",", "")) && !invented.contains(number)) {
                invented.add(number);
            }
        }

        return invented;

    }

    private record Prepared(long cardId, InterviewStarSlot slot, String question) {
    }
}
