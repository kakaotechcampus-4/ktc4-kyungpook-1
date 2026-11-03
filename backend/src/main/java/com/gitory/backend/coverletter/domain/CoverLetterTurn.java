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

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 초안 뒤 재료를 채우는 대화의 말 한 줄로, 자소서 하나에 대화 하나라 문항을 옮겨도 seq 가 이어진다
 * 문항은 공개 id 가 아니라 AI 와 주고받는 문항 번호(seq)로 가리킨다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "cover_letter_turn")
public class CoverLetterTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long coverLetterId;
    private int questionSeq;
    private int seq;

    @Enumerated(EnumType.STRING)
    private TurnRole role;

    private String text;

    @Enumerated(EnumType.STRING)
    private MaterialItem item;

    @Enumerated(EnumType.STRING)
    private TurnStatus status;

    private Instant createdAt;

    public static CoverLetterTurn user(Long coverLetterId, int questionSeq, int seq, String text) {

        return of(coverLetterId, questionSeq, seq, TurnRole.USER, text, null, null);

    }

    public static CoverLetterTurn ai(Long coverLetterId, int questionSeq, int seq, String text, MaterialItem item,
                                     TurnStatus status) {

        return of(coverLetterId, questionSeq, seq, TurnRole.AI, text, item, status);

    }

    private static CoverLetterTurn of(Long coverLetterId, int questionSeq, int seq, TurnRole role, String text,
                                      MaterialItem item, TurnStatus status) {

        CoverLetterTurn turn = new CoverLetterTurn();
        turn.coverLetterId = coverLetterId;
        turn.questionSeq = questionSeq;
        turn.seq = seq;
        turn.role = role;
        turn.text = text;
        turn.item = item;
        turn.status = status;
        turn.createdAt = CoverLetterTime.now();

        return turn;

    }
}
