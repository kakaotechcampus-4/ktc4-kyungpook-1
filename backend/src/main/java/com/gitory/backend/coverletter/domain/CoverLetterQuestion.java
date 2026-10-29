package com.gitory.backend.coverletter.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 자소서 문항 하나와 그 배정 카드·질문과 답·초안이다
 * 카드를 지우면 DB 가 카드 칸만 비우고 문항은 남긴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "cover_letter_question")
public class CoverLetterQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long coverLetterId;
    private short seq;
    private String text;
    private int charLimit;

    @Enumerated(EnumType.STRING)
    private QuestionTheme theme;

    private Long primaryCardId;
    private Long supportingCardId;

    @Enumerated(EnumType.STRING)
    private AssignedBy assignedBy;

    private String pickReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ConsideredCard> considered;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialAsk> asks;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialAnswer> answers;

    private String aiText;
    private String draftText;
    private boolean edited;
    private Integer charCount;
    private Integer overBy;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<DraftSentence> sentences;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<DraftMark> marks;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialItem> blanks;

    @Enumerated(EnumType.STRING)
    private DraftSource draftSource;

    public static CoverLetterQuestion of(Long coverLetterId, int seq, String text, int charLimit) {

        CoverLetterQuestion question = new CoverLetterQuestion();
        question.publicId = UUID.randomUUID();
        question.coverLetterId = coverLetterId;
        question.seq = (short) seq;
        question.text = text;
        question.charLimit = charLimit;
        question.considered = new ArrayList<>();
        question.asks = new ArrayList<>();
        question.answers = new ArrayList<>();
        question.sentences = new ArrayList<>();
        question.marks = new ArrayList<>();
        question.blanks = new ArrayList<>();

        return question;

    }

    public void assign(QuestionTheme theme, Long primaryCardId, Long supportingCardId, AssignedBy assignedBy,
                       String pickReason, List<ConsideredCard> considered) {

        this.theme = theme;
        this.primaryCardId = primaryCardId;
        this.supportingCardId = supportingCardId;
        this.assignedBy = assignedBy;
        this.pickReason = pickReason;
        this.considered = new ArrayList<>(considered);

    }

    /** 사용자가 고른 카드로 바꾸며, AI 가 고른 이유는 더는 이 카드에 맞지 않아 지운다 */
    public void chooseCards(Long primaryCardId, Long supportingCardId) {

        this.primaryCardId = primaryCardId;
        this.supportingCardId = supportingCardId;
        this.assignedBy = AssignedBy.USER;
        this.pickReason = null;

    }
}
