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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 자소서 한 편과 그 단계 상태다
 * AI 단계(ASSIGNING·ASKING·DRAFTING)는 runningSince 가 비어 있으면 줄 서 있고, 차 있으면 워커가 돌리는 중이다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "cover_letter")
public class CoverLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long userId;
    private String jobRole;
    private String memo;

    @Enumerated(EnumType.STRING)
    private CoverLetterStage stage;

    private Instant runningSince;

    @Enumerated(EnumType.STRING)
    private CoverLetterStep failedStep;

    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<AssignStep> assignSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<DraftStep> draftSteps;

    private String idempotencyKey;
    private Instant createdAt;
    private Instant updatedAt;

    /** 만들자마자 카드 고르기를 기다리고, 메모가 비어 있으면 없음으로 둔다 */
    public static CoverLetter start(Long userId, String jobRole, String memo, String idempotencyKey) {

        CoverLetter letter = new CoverLetter();
        Instant now = CoverLetterTime.now();
        letter.publicId = UUID.randomUUID();
        letter.userId = userId;
        letter.jobRole = jobRole;
        letter.memo = memo == null || memo.isBlank() ? null : memo;
        letter.stage = CoverLetterStage.ASSIGNING;
        letter.assignSteps = new ArrayList<>();
        letter.draftSteps = new ArrayList<>();
        letter.idempotencyKey = idempotencyKey;
        letter.createdAt = now;
        letter.updatedAt = now;

        return letter;

    }
}
