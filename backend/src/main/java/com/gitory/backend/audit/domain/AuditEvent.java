package com.gitory.backend.audit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/** 감사 기록 한 건으로, 요청 내용은 남기지 않고 누가 어떤 대상에 무엇을 했고 결과가 어땠는지만 남긴다 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    private Long subjectId;

    @Enumerated(EnumType.STRING)
    private AuditOutcome outcome;

    private String requestId;

    // DB 기본값 now() 에 맡기면 JPA 가 null 을 넣어 NOT NULL 제약에 걸린다
    @CreationTimestamp
    private Instant occurredAt;

    private AuditEvent(Long userId, AuditAction action, Long subjectId, AuditOutcome outcome, String requestId) {
        this.userId = userId;
        this.action = action;
        this.subjectId = subjectId;
        this.outcome = outcome;
        this.requestId = requestId;
    }

    static AuditEvent of(Long userId, AuditAction action, Long subjectId, AuditOutcome outcome, String requestId) {

        return new AuditEvent(userId, action, subjectId, outcome, requestId);

    }
}
