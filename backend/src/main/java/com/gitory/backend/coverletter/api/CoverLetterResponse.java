package com.gitory.backend.coverletter.api;

import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterStep;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.coverletter.domain.Strength;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "자소서 한 편과 문항별 배정 카드·질문과 답")
public record CoverLetterResponse(
        @Schema(format = "uuid") String id,
        CoverLetterStage stage,
        CoverLetterStep failedStep,
        @Schema(description = "AI_UNAVAILABLE 또는 INTERNAL_ERROR. 실패가 아니면 null") String errorCode,
        @Schema(description = "실패일 때만 값이 있고, true 면 다시 시도할 수 있다") Boolean retryable,
        @Schema(description = "AI 가 도는 단계면 다음 조회 간격(밀리초), 아니면 null", example = "1500") Integer pollAfterMs,
        String jobRole,
        String memo,
        @Schema(description = "만들 때 고른 강점, 0~3개. 고른 것이 없으면 빈 배열") List<Strength> strengths,
        @Schema(description = "질문 단계가 지원 이유를 글에 쓸 수 있다고 봤으면 true, 쓰기 어렵다고 봤으면 false(이때 초안은 지원 이유를 빈칸으로 두고 대화에서 다시 묻는다). 판정 전이거나 지원 이유가 없는 옛 자소서면 null") Boolean memoUsable,
        @Schema(description = "공고 상세에서 시작한 자소서면 그 공고, 아니면 null") CoverLetterPostingResponse posting,
        Instant createdAt,
        Instant updatedAt,
        List<CoverLetterView.Question> questions,
        Steps steps) {

    // AI 가 도는 단계를 화면이 조금 늦게 보여도 되는 만큼만 자주 읽는다
    static final int POLL_AFTER_MS = 1500;

    public record Steps(List<CoverLetterView.Step> assign, List<CoverLetterView.WritingStep> draft) {
    }

    static CoverLetterResponse from(CoverLetterView letter) {
        return new CoverLetterResponse(
                letter.id().toString(),
                letter.stage(),
                letter.failedStep(),
                letter.errorCode(),
                letter.retryable(),
                letter.running() ? POLL_AFTER_MS : null,
                letter.jobRole(),
                letter.memo(),
                letter.strengths(),
                letter.memoUsable(),
                CoverLetterPostingResponse.from(letter.posting()),
                letter.createdAt(),
                letter.updatedAt(),
                letter.questions(),
                new Steps(letter.assignSteps(), letter.draftSteps()));
    }
}
