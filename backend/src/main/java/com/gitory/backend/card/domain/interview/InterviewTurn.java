package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

/**
 * 카드 하나에서 AI 가 던진 질문과 그에 대한 답이고, 카드 안의 질문 번호는 seq 다
 * 답 글이 남아 있어도 결과(outcome)가 ANSWERED 가 아니면 답한 질문이 아니다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "interview_turn")
public class InterviewTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    private short seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1)
    private StarField starSlot;

    @Enumerated(EnumType.STRING)
    private QuestionType questionType;

    @Enumerated(EnumType.STRING)
    private TriggerSource triggerSource;

    private Long parentTurnId;

    private String questionText;

    private String answerText;

    private String answerOriginal;

    @Enumerated(EnumType.STRING)
    private AnswerSource answerSource;

    @Enumerated(EnumType.STRING)
    private TurnOutcome outcome;

    @Enumerated(EnumType.STRING)
    private AnswerMode answerMode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> suggestedAnswers = new ArrayList<>();

    private String choiceReason;

    private Instant askedAt;

    private Instant answeredAt;

    private InterviewTurn(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                          String questionText, TriggerSource triggerSource, Long parentTurnId) {
        this(cardId, seq, starSlot, questionType, questionText, triggerSource, parentTurnId, null, List.of(), null);
    }

    private InterviewTurn(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                          String questionText, TriggerSource triggerSource, Long parentTurnId,
                          AnswerMode answerMode, List<String> suggestedAnswers, String choiceReason) {
        this.cardId = cardId;
        this.seq = seq;
        this.starSlot = starSlot;
        this.questionType = questionType;
        this.triggerSource = triggerSource;
        this.parentTurnId = parentTurnId;
        this.questionText = questionText;
        this.answerMode = answerMode;
        this.suggestedAnswers = new ArrayList<>(suggestedAnswers);
        this.choiceReason = choiceReason;
        this.askedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 사용자가 칸을 골라 요청한 질문을 답이 없는 채로 만들고, 질문한 쪽은 AI 가 돌려준 값을 쓰지 않고 늘 USER 로 둔다
     * 질문 시각은 DB 가 저장하는 µs 까지만 남겨 저장 직후의 값과 다시 읽은 값이 같게 한다
     */
    public static InterviewTurn ask(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                                    String questionText) {

        return new InterviewTurn(cardId, seq, starSlot, questionType, questionText, TriggerSource.USER, null);

    }

    /**
     * 되묻기 루프에서 AI 가 정한 칸·답 버튼·예상 답변·판단 이유로 질문을 만든다
     * 사용자가 칸을 지정했으면 USER, AI 가 칸을 골랐으면 AUTO 이고 AUTO 는 바로 앞 질문을 부모로 남긴다
     */
    public static InterviewTurn asked(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                                      String questionText, boolean slotChosenByUser, Long previousTurnId,
                                      AnswerMode answerMode, List<String> suggestedAnswers, String choiceReason) {

        return slotChosenByUser
                ? new InterviewTurn(cardId, seq, starSlot, questionType, questionText, TriggerSource.USER, null,
                        answerMode, suggestedAnswers, choiceReason)
                : new InterviewTurn(cardId, seq, starSlot, questionType, questionText, TriggerSource.AUTO,
                        previousTurnId, answerMode, suggestedAnswers, choiceReason);

    }

    /** 답이 모자라 AI 가 같은 칸에 다시 묻는 질문으로, 사용자가 요청한 것이 아니므로 AUTO 이고 어느 질문의 후속인지 남긴다 */
    public static InterviewTurn followUp(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                                         String questionText, Long parentTurnId) {

        return new InterviewTurn(cardId, seq, starSlot, questionType, questionText, TriggerSource.AUTO,
                parentTurnId);

    }

    /**
     * 사용자가 쓰거나 보기에서 고른 답을 가공 없이 담아 답한 질문으로 바꾸고, 답한 시각도 질문 시각처럼 µs 까지만 남긴다
     * 결과가 정해진 질문(답함·넘김 등)은 바뀌지 않아야 하므로 어디서 불러도 예외를 던진다
     */
    public void answer(String text, AnswerSource source) {

        close(TurnOutcome.ANSWERED, text, source);

    }

    /** 다듬기 미리 보기를 거친 답은 다듬기 전 사용자 답도 남겨 '내 답 → 다듬은 문장' 을 다시 보여 준다 */
    public void answer(String text, AnswerSource source, String original) {

        close(TurnOutcome.ANSWERED, text, source);
        this.answerOriginal = original;

    }

    /** R 수치 질문에 '재 봤어요' 로 확정한 문장을 담는다 */
    public void measure(String text, AnswerSource source) {

        close(TurnOutcome.MEASURED, text, source);

    }

    /** '측정 안 했어요' 를 기록하며, 숫자 없는 문장을 함께 고르지 않았으면 글과 출처는 비어 있다 */
    public void notMeasured(String text, AnswerSource source) {

        close(TurnOutcome.NOT_MEASURED, text, source);

    }

    private void close(TurnOutcome result, String text, AnswerSource source) {

        requireOpen();
        this.answerText = text;
        this.answerSource = source;
        this.outcome = result;
        this.answeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    }

    /** 답 글과 출처 없이 넘긴 질문으로 바꾸며, 넘긴 질문도 이미 만든 질문이라 질문 상한에 그대로 센다 */
    public void skip() {

        requireOpen();
        this.outcome = TurnOutcome.SKIPPED;
        this.answeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    }

    private void requireOpen() {

        if (isClosed()) {
            throw new IllegalStateException("이미 결과가 정해진 질문이다: " + outcome);
        }

    }

    public boolean isAnswered() {

        return outcome == TurnOutcome.ANSWERED;

    }

    /** 답했거나 넘겼거나 측정 결과를 정한 질문이면 true 이고, 결과가 없거나 LATER·INSUFFICIENT 인 질문은 아직 답을 기다린다 */
    public boolean isClosed() {

        return outcome == TurnOutcome.ANSWERED || outcome == TurnOutcome.MEASURED
                || outcome == TurnOutcome.NOT_MEASURED || outcome == TurnOutcome.SKIPPED;

    }

    /** 옛 질문은 답 버튼을 저장하지 않았으므로 글로 답하는 질문으로 본다 */
    public AnswerMode answerModeOrText() {

        return answerMode == null ? AnswerMode.TEXT : answerMode;

    }
}
