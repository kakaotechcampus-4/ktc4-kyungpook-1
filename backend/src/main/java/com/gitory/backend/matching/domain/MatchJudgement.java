package com.gitory.backend.matching.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * AI 가 카드 하나가 공고 조건 하나를 채우는지 판단한 결과이며, 같은 카드 글로는 다시 묻지 않도록 카드 내용 해시와 함께 둔다
 * 두 계산이 같은 짝을 함께 저장할 수 있어 저장은 저장소의 '없을 때만 넣기' 로만 한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "match_judgement")
public class MatchJudgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String cardHash;

    private Long criterionId;

    private boolean met;

    private String reason;

    private Instant judgedAt;
}
