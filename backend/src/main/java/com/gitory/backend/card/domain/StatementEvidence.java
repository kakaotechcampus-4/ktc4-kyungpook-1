package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/** 카드 문장 하나와 그 근거 커밋 하나를 잇는다 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "statement_evidence")
public class StatementEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    private Long commitId;

    private StatementEvidence(Long statementId, Long commitId) {
        this.statementId = statementId;
        this.commitId = commitId;
    }

    static StatementEvidence link(Long statementId, Long commitId) {

        return new StatementEvidence(statementId, commitId);

    }
}
