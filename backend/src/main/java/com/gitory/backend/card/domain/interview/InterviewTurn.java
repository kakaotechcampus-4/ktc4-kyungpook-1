package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    @Enumerated(EnumType.STRING)
    private AnswerSource answerSource;

    @Enumerated(EnumType.STRING)
    private TurnOutcome outcome;

    private Instant askedAt;

    private Instant answeredAt;

    private InterviewTurn(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                          String questionText, TriggerSource triggerSource, Long parentTurnId) {
        this.cardId = cardId;
        this.seq = seq;
        this.starSlot = starSlot;
        this.questionType = questionType;
        this.triggerSource = triggerSource;
        this.parentTurnId = parentTurnId;
        this.questionText = questionText;
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

    /** 답이 모자라 AI 가 같은 칸에 다시 묻는 질문으로, 사용자가 요청한 것이 아니므로 AUTO 이고 어느 질문의 후속인지 남긴다 */
    public static InterviewTurn followUp(Long cardId, short seq, StarField starSlot, QuestionType questionType,
                                         String questionText, Long parentTurnId) {

        return new InterviewTurn(cardId, seq, starSlot, questionType, questionText, TriggerSource.AUTO,
                parentTurnId);

    }

    /**
     * 사용자가 직접 쓴 답을 가공 없이 담아 답한 질문으로 바꾸고, 답한 시각도 질문 시각처럼 µs 까지만 남긴다
     * 이미 답한 질문의 답은 바뀌지 않아야 하므로 어디서 불러도 예외를 던진다
     */
    public void answerTyped(String text) {

        if (isAnswered()) {
            throw new IllegalStateException("이미 답한 질문이다");
        }

        this.answerText = text;
        this.answerSource = AnswerSource.TYPED;
        this.outcome = TurnOutcome.ANSWERED;
        this.answeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    }

    public boolean isAnswered() {

        return outcome == TurnOutcome.ANSWERED;

    }
}
