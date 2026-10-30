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

    public boolean isRunning() {

        return stage == CoverLetterStage.ASSIGNING || stage == CoverLetterStage.ASKING
                || stage == CoverLetterStage.DRAFTING;

    }

    /** 워커가 집은 시각을 남겨, 다른 워커가 같은 단계를 다시 집지 않고 멈춘 단계를 가려낼 수 있게 한다 */
    public void claim() {

        runningSince = CoverLetterTime.now();

    }

    public void assigned(List<AssignStep> steps) {

        stage = CoverLetterStage.ASSIGNED;
        runningSince = null;
        assignSteps = new ArrayList<>(steps);
        updatedAt = CoverLetterTime.now();

    }

    /** 카드 고르기가 끝난 자소서에서만 부르며, 지금 배정으로 질문 만들기를 줄 세운다 */
    public void startAsking() {

        stage = CoverLetterStage.ASKING;
        updatedAt = CoverLetterTime.now();

    }

    public void asked() {

        stage = CoverLetterStage.ASKED;
        runningSince = null;
        updatedAt = CoverLetterTime.now();

    }

    /** 질문을 받은 자소서에서만 부르며, 지금 답으로 초안 쓰기를 줄 세운다 */
    public void startDrafting() {

        stage = CoverLetterStage.DRAFTING;
        updatedAt = CoverLetterTime.now();

    }

    public void drafted(List<DraftStep> steps) {

        stage = CoverLetterStage.DRAFTED;
        runningSince = null;
        draftSteps = new ArrayList<>(steps);
        updatedAt = CoverLetterTime.now();

    }

    /** 도는 단계에서만 부르며, 그 단계를 실패한 단계로 남겨 다시 시도가 그 단계부터 돌게 한다 */
    public void fail(CoverLetterError error) {

        failedStep = switch (stage) {
            case ASSIGNING -> CoverLetterStep.ASSIGN;
            case ASKING -> CoverLetterStep.ASK;
            case DRAFTING -> CoverLetterStep.DRAFT;
            default -> throw new IllegalStateException("도는 단계가 아닌 자소서는 실패로 바꾸지 않는다: " + stage);
        };
        stage = CoverLetterStage.FAILED;
        errorCode = error.name();
        runningSince = null;
        updatedAt = CoverLetterTime.now();

    }

    /** 서버가 다시 뜨면 돌던 단계를 줄 세워, 워커가 처음부터 다시 돌게 한다 */
    public void requeue() {

        runningSince = null;

    }

    public void retry() {

        stage = switch (failedStep) {
            case ASSIGN -> CoverLetterStage.ASSIGNING;
            case ASK -> CoverLetterStage.ASKING;
            case DRAFT -> CoverLetterStage.DRAFTING;
        };
        failedStep = null;
        errorCode = null;
        updatedAt = CoverLetterTime.now();

    }

    public void touch() {

        updatedAt = CoverLetterTime.now();

    }
}
