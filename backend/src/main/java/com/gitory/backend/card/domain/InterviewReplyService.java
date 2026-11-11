package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.InterviewReplyExtra;
import com.gitory.backend.agent.port.InterviewReplyPort;
import com.gitory.backend.agent.port.InterviewReplyRequest;
import com.gitory.backend.agent.port.InterviewReplyResult;
import com.gitory.backend.agent.port.InterviewReplySlot;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.AnswerSource;
import com.gitory.backend.card.domain.interview.InterviewAskService;
import com.gitory.backend.card.domain.interview.InterviewHistoryService;
import com.gitory.backend.card.domain.interview.InterviewLimits;
import com.gitory.backend.card.domain.interview.InterviewTurn;
import com.gitory.backend.card.domain.interview.InterviewTurnAnsweredException;
import com.gitory.backend.card.domain.interview.InterviewTurnNotFoundException;
import com.gitory.backend.card.domain.interview.InterviewTurnView;
import com.gitory.backend.card.domain.interview.QuestionType;
import com.gitory.backend.card.domain.interview.TurnOutcome;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 되묻기 답을 AI 가 먼저 읽게 해, 쓸 내용이면 AI 가 쓴 문장을 칸에 넣고 모자라면 같은 칸의 꼬리 질문을 만든다
 * AI 를 기다리는 동안 트랜잭션과 행 잠금을 쥐지 않도록 읽기, AI 호출, 짧은 저장을 따로 열고, 저장할 때 카드의 버전이 그대로인지 다시 본다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InterviewLimits.class)
public class InterviewReplyService {

    private static final int MAX_HELD_ANSWERS = 2;
    private static final int FOLLOW_UP_MAX_LENGTH = 500;
    private static final int REASON_MAX_LENGTH = 60;

    private final CardOwnershipService ownership;
    private final CardGenerationGuard generation;
    private final InterviewTurnRepository turns;
    private final CardVersionReader reader;
    private final InterviewAnswerService answers;
    private final CardThemeSuggester themeSuggester;
    private final CardDetailService details;
    private final InterviewHistoryService history;
    private final InterviewReplyPort replier;
    private final UserRateLimiter rateLimiter;
    private final InterviewLimits limits;
    private final PlatformTransactionManager transactionManager;

    /**
     * 질문 번호(404), 초안을 만드는 중인 카드(409), 이미 끝난 질문(같은 답이면 같은 결과, 다르면 409), 답의 모양(400) 순서로
     * 확인하고, 끝난 질문의 재시도는 AI 를 부르지 않고 호출 한도에도 세지 않는다
     */
    public InterviewReply reply(UUID publicId, Long userId, int turnNo, String text, String source) {

        Prepared prepared = snapshot().execute(status -> prepare(publicId, userId, turnNo, text, source));
        if (prepared.replayed() != null) {
            return finish(publicId, userId, prepared.replayed());
        }

        rateLimiter.acquire(RateLimitedAction.INTERVIEW_ANSWER, userId, 1);
        InterviewReplyResult verdict;
        try {
            verdict = AiCallContext.callForCard(prepared.request().cardId(), () -> replier.reply(prepared.request()));
        } catch (AgentUnavailableException failure) {
            log.warn("카드 {} 의 되묻기 답을 AI 가 읽지 못해 아무것도 저장하지 않는다", prepared.request().cardId(), failure);
            return finish(publicId, userId, Decision.of(InterviewReplyOutcome.UNPOLISHED));
        }
        if (unusable(verdict, prepared, text)) {
            log.warn("카드 {} 의 되묻기 답 판단이 규칙에 맞지 않아 아무것도 저장하지 않는다", prepared.request().cardId());
            return finish(publicId, userId, Decision.of(InterviewReplyOutcome.UNPOLISHED));
        }

        Decision decision = new TransactionTemplate(transactionManager)
                .execute(status -> save(publicId, userId, turnNo, text, prepared, verdict));

        return finish(publicId, userId, decision);

    }

    private TransactionTemplate snapshot() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return template;

    }

    private Prepared prepare(UUID publicId, Long userId, int turnNo, String text, String source) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.EDIT_CARD);
        InterviewTurn turn = turnOf(card, turnNo);
        generation.requireNotGenerating(card);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        if (turn.isClosed()) {
            return Prepared.replayed(replayOf(turn, text, asked));
        }

        AnswerSource answerSource = InterviewAnswerService.checkedAnswer(text, source);
        VersionSlots version = reader.read(card.getId(), card.getCurrentVersion());
        List<String> held = heldAnswersOf(turn, asked);
        long sameSlotAsks = asked.stream().filter(each -> each.getStarSlot() == turn.getStarSlot()).count();
        boolean followUpAllowed = held.isEmpty() && asked.size() < limits.maxTurns()
                && sameSlotAsks < InterviewAskService.MAX_ASKS_PER_SLOT;
        List<InterviewReplySlot> slots = new ArrayList<>();
        for (StarField slot : StarField.values()) {
            slots.add(new InterviewReplySlot(InterviewStarSlot.valueOf(slot.name()), version.textOf(slot)));
        }

        InterviewReplyRequest request = new InterviewReplyRequest(card.getId(), card.getTitle(),
                InterviewStarSlot.valueOf(turn.getStarSlot().name()), turn.getQuestionText(), text.strip(), held,
                slots, followUpAllowed);

        return Prepared.toAsk(card.getCurrentVersion(), answerSource, version.textOf(turn.getStarSlot()), request);

    }

    // 이어 묻는 질문의 부모가 같은 칸의 모자란 답이면 그 답들을 오래된 것부터 돌려주고, 가까운 것부터 두 개까지만 모은다
    private static List<String> heldAnswersOf(InterviewTurn turn, List<InterviewTurn> asked) {

        Map<Long, InterviewTurn> byId = new LinkedHashMap<>();
        asked.forEach(each -> byId.put(each.getId(), each));
        List<String> held = new ArrayList<>();
        InterviewTurn child = turn;

        while (held.size() < MAX_HELD_ANSWERS && child.getParentTurnId() != null) {
            InterviewTurn parent = byId.get(child.getParentTurnId());
            if (parent == null || parent.getOutcome() != TurnOutcome.INSUFFICIENT
                    || parent.getStarSlot() != turn.getStarSlot()) {
                break;
            }
            held.addFirst(parent.getAnswerText());
            child = parent;
        }

        return held;

    }

    // 끝난 질문에 같은 답이 다시 오면 처음 결과를 다시 알려 주고, 넘긴 질문은 어떤 글이 와도 넘김이다
    private Decision replayOf(InterviewTurn turn, String text, List<InterviewTurn> asked) {

        if (turn.getOutcome() == TurnOutcome.SKIPPED) {
            return Decision.of(InterviewReplyOutcome.SKIPPED);
        }
        if (turn.getOutcome() == TurnOutcome.ANSWERED && Objects.equals(turn.getAnswerOriginal(), text)) {
            return Decision.applied(List.of(new WrittenSlot(turn.getStarSlot(), turn.getAnswerText())), null);
        }
        if (turn.getOutcome() == TurnOutcome.INSUFFICIENT && Objects.equals(turn.getAnswerText(), text)) {
            Optional<InterviewTurn> followUp = asked.stream()
                    .filter(each -> Objects.equals(each.getParentTurnId(), turn.getId()))
                    .findFirst();

            return Decision.askMore(followUp.map(InterviewTurn::getChoiceReason).orElse(null),
                    followUp.map(each -> (int) each.getSeq()).orElse(null));
        }

        throw new InterviewTurnAnsweredException();

    }

    // AI 가 준 값이 칸에 저장하거나 질문으로 보일 수 없으면 쓰지 않는다
    private static boolean unusable(InterviewReplyResult verdict, Prepared prepared, String text) {

        return switch (verdict.verdict()) {
            case APPLY -> unusableSentence(verdict.sentence(), prepared, text);
            case ASK_MORE -> !prepared.request().followUpAllowed() || isBlank(verdict.followUp())
                    || verdict.followUp().length() > FOLLOW_UP_MAX_LENGTH
                    || verdict.followUp().indexOf('\u0000') >= 0;
            case OFF_TOPIC, DONT_KNOW -> false;
        };

    }

    // 숫자는 사용자가 친 답, 앞서 둔 답, 그 칸의 기존 글에 있는 것만 쓸 수 있다
    private static boolean unusableSentence(String sentence, Prepared prepared, String text) {

        if (isBlank(sentence) || sentence.length() > DraftSaveService.SLOT_MAX_LENGTH
                || sentence.indexOf('\u0000') >= 0) {
            return true;
        }

        List<String> known = new ArrayList<>(List.of(text));
        known.addAll(prepared.request().heldAnswers());
        if (prepared.askedSlotText() != null) {
            known.add(prepared.askedSlotText());
        }

        return !InterviewPolishedDraftService.inventedNumbers(sentence, String.join("\n", known)).isEmpty();

    }

    private static boolean isBlank(String value) {

        return value == null || CardText.isBlank(value);

    }

    private Decision save(UUID publicId, Long userId, int turnNo, String text, Prepared prepared,
                          InterviewReplyResult verdict) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        InterviewTurn turn = turnOf(card, turnNo);
        generation.requireNotGenerating(card);
        List<InterviewTurn> asked = turns.findByCardIdOrderBySeqAsc(card.getId());

        if (turn.isClosed()) {
            return replayOf(turn, text, asked);
        }
        if (card.getCurrentVersion() != prepared.versionNo()) {
            throw new InterviewReplyStaleException();
        }

        return switch (verdict.verdict()) {
            case APPLY -> apply(card, turn, text, prepared, verdict);
            case ASK_MORE -> askMore(card, turn, text, prepared, asked, verdict);
            case OFF_TOPIC -> Decision.notUsed(cut(verdict.reason(), REASON_MAX_LENGTH));
            case DONT_KNOW -> {
                turn.skip();

                yield Decision.of(InterviewReplyOutcome.SKIPPED);
            }
        };

    }

    private Decision apply(Card card, InterviewTurn turn, String text, Prepared prepared,
                           InterviewReplyResult verdict) {

        VersionSlots version = reader.read(card.getId(), card.getCurrentVersion());
        Map<StarField, String> texts = new LinkedHashMap<>();
        texts.put(turn.getStarSlot(), verdict.sentence());
        for (InterviewReplyExtra extra : verdict.extra()) {
            StarField slot = StarField.valueOf(extra.starSlot().name());
            if (usableExtra(extra, slot, turn, version, texts, text)) {
                texts.put(slot, extra.sentence().strip());
            }
        }

        answers.writeSlots(card, turn, texts, prepared.answerSource());
        turn.answer(verdict.sentence(), prepared.answerSource(), text);
        themeSuggester.settle(card, reader.read(card.getId(), card.getCurrentVersion()));

        List<WrittenSlot> written = texts.entrySet().stream()
                .map(entry -> new WrittenSlot(entry.getKey(), entry.getValue()))
                .toList();

        return Decision.applied(written, prepared.versionNo());

    }

    // 비어 있는 다른 칸에 한 번만, 칸에 담을 수 있는 길이로, 사용자가 친 답에 있는 숫자만 쓴 문장일 때 넣는다
    private static boolean usableExtra(InterviewReplyExtra extra, StarField slot, InterviewTurn turn,
                                       VersionSlots version, Map<StarField, String> texts, String typed) {

        if (slot == turn.getStarSlot() || texts.containsKey(slot) || version.stateOf(slot) != StarFieldState.EMPTY) {
            return false;
        }
        String sentence = extra.sentence().strip();

        return !sentence.isEmpty() && !CardText.isBlank(sentence)
                && sentence.length() <= DraftSaveService.SLOT_MAX_LENGTH && sentence.indexOf('\u0000') < 0
                && InterviewPolishedDraftService.inventedNumbers(sentence, typed).isEmpty();

    }

    private Decision askMore(Card card, InterviewTurn turn, String text, Prepared prepared, List<InterviewTurn> asked,
                             InterviewReplyResult verdict) {

        String reason = cut(verdict.reason(), REASON_MAX_LENGTH);
        turn.insufficient(text, prepared.answerSource());
        InterviewTurn followUp = turns.save(InterviewTurn.asked(card.getId(), nextSeq(asked), turn.getStarSlot(),
                QuestionType.FOLLOWUP, verdict.followUp(), false, turn.getId(), AnswerMode.TEXT, List.of(), reason, null));
        card.resumeInterview();

        return Decision.askMore(reason, (int) followUp.getSeq());

    }

    private static String cut(String value, int max) {

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.length() <= max ? value : value.substring(0, max);

    }

    private static short nextSeq(List<InterviewTurn> asked) {

        int max = asked.stream().mapToInt(InterviewTurn::getSeq).max().orElse(0);

        return (short) (max + 1);

    }

    private InterviewTurn turnOf(Card card, int turnNo) {

        // 질문 번호는 SMALLINT 라, 그 범위 밖의 번호를 short 로 바꾸면 다른 질문을 가리키게 된다
        if (turnNo < 1 || turnNo > Short.MAX_VALUE) {
            throw new InterviewTurnNotFoundException();
        }

        return turns.findByCardIdAndSeq(card.getId(), (short) turnNo)
                .orElseThrow(InterviewTurnNotFoundException::new);

    }

    private InterviewReply finish(UUID publicId, Long userId, Decision decision) {

        CardDetail card = details.load(publicId, userId);
        InterviewTurnView nextTurn = null;
        if (decision.nextTurnNo() != null) {
            nextTurn = history.history(publicId, userId).stream()
                    .filter(view -> view.turnNo() == decision.nextTurnNo())
                    .findFirst()
                    .orElse(null);
        }

        return new InterviewReply(decision.outcome(), decision.reason(), decision.written(),
                decision.restoreVersionNo(), card, nextTurn);

    }

    private record Prepared(Short versionNo, AnswerSource answerSource, String askedSlotText,
                            InterviewReplyRequest request, Decision replayed) {

        static Prepared replayed(Decision decision) {

            return new Prepared(null, null, null, null, decision);

        }

        static Prepared toAsk(short versionNo, AnswerSource answerSource, String askedSlotText,
                              InterviewReplyRequest request) {

            return new Prepared(versionNo, answerSource, askedSlotText, request, null);

        }
    }

    private record Decision(InterviewReplyOutcome outcome, String reason, List<WrittenSlot> written,
                            Short restoreVersionNo, Integer nextTurnNo) {

        static Decision of(InterviewReplyOutcome outcome) {

            return new Decision(outcome, null, List.of(), null, null);

        }

        static Decision notUsed(String reason) {

            return new Decision(InterviewReplyOutcome.NOT_USED, reason, List.of(), null, null);

        }

        static Decision askMore(String reason, Integer nextTurnNo) {

            return new Decision(InterviewReplyOutcome.ASK_MORE, reason, List.of(), null, nextTurnNo);

        }

        static Decision applied(List<WrittenSlot> written, Short restoreVersionNo) {

            return new Decision(InterviewReplyOutcome.APPLIED, null, written, restoreVersionNo, null);

        }
    }
}
