package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewMeasuredSentencePort;
import com.gitory.backend.agent.port.InterviewMeasuredSentenceRequest;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.InvalidInterviewFieldException;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * '재 봤어요' 에 쓴 측정 결과로 R 문장 미리 보기를 만들고, 아무것도 저장하지 않는다
 * 문장에 사용자가 쓰지 않은 숫자가 들어가면 카드에 지어낸 수치가 남으므로, 그때와 AI 실패 때는 지금 R 문장 뒤에 사용자 글을 붙인다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewMeasuredDraftService {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final CardVersionReader reader;
    private final InterviewMeasuredSentencePort sentences;
    private final UserRateLimiter rateLimiter;
    private final PlatformTransactionManager transactionManager;

    /**
     * 질문 번호(404), 초안을 만드는 중인 카드·확정된 카드·결과가 정해진 질문(409), R 수치 질문이 아님(400), 글의 모양(400) 순서로 확인한다
     * 글에 숫자가 없으면 AI 를 부르지 않고 null 을 돌려준다
     */
    public String draft(UUID publicId, Long userId, int turnNo, String text) {

        Prepared prepared = readOnly().execute(status -> prepare(publicId, userId, turnNo, text));
        if (!NUMBER.matcher(text).find()) {
            return null;
        }

        rateLimiter.acquire(RateLimitedAction.INTERVIEW_ANSWER, userId, 1);
        String fallback = fallbackOf(prepared.currentResult(), text);
        String written;
        try {
            written = sentences.write(new InterviewMeasuredSentenceRequest(prepared.cardId(), prepared.cardTitle(),
                    prepared.currentResult(), text.strip()));
        } catch (AgentUnavailableException failure) {
            log.warn("카드 {} 의 수치 문장을 AI 에게서 받지 못해 틀 문장으로 대신한다", prepared.cardId(), failure);
            return fallback;
        }

        if (!numbersOf(text).containsAll(numbersOf(written))) {
            log.warn("카드 {} 의 수치 문장에 사용자가 쓰지 않은 숫자가 있어 틀 문장으로 대신한다", prepared.cardId());
            return fallback;
        }

        return written;

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
        if (turn.getStarSlot() != StarField.R || turn.answerModeOrText() != AnswerMode.MEASURE) {
            throw new InvalidInterviewFieldException();
        }
        checkedText(text);

        SlotContent result = reader.read(card.getId(), card.getCurrentVersion()).slots().get(StarField.R);

        return new Prepared(card.getId(), card.getTitle(), result == null ? null : result.body());

    }

    private static void checkedText(String text) {

        if (text == null || CardText.isBlank(text)) {
            throw new InvalidCardInputException("측정 결과를 입력해 주세요.");
        }

        CardText.requireNoNul(text, "측정 결과");

        if (text.length() > DraftSaveService.SLOT_MAX_LENGTH) {
            throw new InvalidCardInputException("측정 결과는 " + DraftSaveService.SLOT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

    }

    private static String fallbackOf(String currentResult, String text) {

        if (currentResult == null || CardText.isBlank(currentResult)) {
            return text.strip();
        }

        return currentResult.strip() + " (" + text.strip() + ")";

    }

    // 1,000 과 1000 을 같은 수로 보도록 쉼표를 지우고 비교한다
    private static Set<String> numbersOf(String text) {

        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group().replace(",", ""));
        }

        return numbers;

    }

    private record Prepared(long cardId, String cardTitle, String currentResult) {
    }
}
