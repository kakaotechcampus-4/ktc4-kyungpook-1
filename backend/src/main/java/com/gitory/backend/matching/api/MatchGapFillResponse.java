package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.GapFillAction;
import com.gitory.backend.matching.domain.GapFillCondition;
import com.gitory.backend.matching.domain.GapFillDecision;
import com.gitory.backend.matching.domain.GapFillProposal;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillStatus;
import com.gitory.backend.matching.domain.GapFillStepKind;
import com.gitory.backend.matching.domain.GapFillView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MatchGapFillResponse(
        @Schema(description = "RUNNING 은 찾는 중, DONE 은 끝남, FAILED 는 실패했거나 찾기가 멈춤(다시 시작하면 새로 찾음)") GapFillStatus state,
        @Schema(description = "RUNNING 일 때만 다음 조회까지 기다릴 밀리초. 끝났으면 null", nullable = true) Integer pollAfterMs,
        @Schema(description = "찾아본 조건마다 하나, 공고 조건 순서. 못 채운 필수 조건(없으면 우대 조건) 최대 5개") List<Condition> conditions) {

    public record Condition(String conditionId,
                            @Schema(description = "찾아볼지. null 은 아직 정하는 중", nullable = true) GapFillDecision decision,
                            @Schema(description = "찾아볼지 정한 이유 한 줄", nullable = true) String decisionReason,
                            @Schema(description = "사용자가 그래도 찾아보기로 다시 찾게 한 조건. decision 은 AI 판단 그대로라 SKIP 이어도 찾은 결과가 온다") boolean forced,
                            @Schema(description = "AI 가 한 일. 생기는 대로 늘어난다") List<Step> steps,
                            @Schema(description = "찾았으면 제안 하나, 못 찾았으면 null", nullable = true) Proposal proposal,
                            @Schema(description = "끝났을 때의 결론. null 은 아직 찾는 중", nullable = true) GapFillResult result) {
    }

    public record Step(GapFillStepKind kind,
                       @Schema(description = "본 후보·카드 수나 찾은 커밋 수", nullable = true) Integer count,
                       @Schema(description = "GITHUB_SEARCHED·GITHUB_FAILED 의 검색어", nullable = true) String query,
                       @Schema(description = "OUTSIDE_REPO_FOUND 의 레포(owner/name). 내 레포 목록에 없어 분석할 수 없다", nullable = true) String repoName) {
    }

    public record Proposal(GapFillAction action,
                           @Schema(description = "CREATE_CARD 일 때 카드로 만들 후보 id", nullable = true) String candidateId,
                           @Schema(description = "CONTINUE_CARD 일 때 마저 채울 작성 중 카드 id", nullable = true) String cardId,
                           @Schema(description = "ANALYZE_REPO 는 분석할 레포 id, CREATE_CARD 는 후보가 나온 레포 id(카드 만들기 API 경로에 쓴다). CONTINUE_CARD 는 null", nullable = true) String repoId,
                           @Schema(description = "후보 제목·작성 중 카드 제목·레포 이름(owner/name)") String title,
                           @Schema(description = "후보가 나온 레포나 분석할 레포(owner/name). CONTINUE_CARD 는 null", nullable = true) String repoName,
                           String reason) {
    }

    static MatchGapFillResponse from(GapFillView view) {

        return new MatchGapFillResponse(view.state(), view.pollAfterMs(),
                view.conditions().stream().map(MatchGapFillResponse::condition).toList());

    }

    private static Condition condition(GapFillCondition condition) {

        return new Condition(condition.conditionId().toString(), condition.decision(), condition.decisionReason(),
                condition.forced(), condition.steps().stream()
                        .map(step -> new Step(step.kind(), step.count(), step.query(), step.repoName()))
                        .toList(),
                condition.proposal() == null ? null : proposal(condition.proposal()), condition.result());

    }

    private static Proposal proposal(GapFillProposal proposal) {

        String target = proposal.targetId().toString();
        return new Proposal(proposal.action(), proposal.action() == GapFillAction.CREATE_CARD ? target : null,
                proposal.action() == GapFillAction.CONTINUE_CARD ? target : null,
                proposal.repoId() == null ? null : proposal.repoId().toString(), proposal.title(),
                proposal.repoName(), proposal.reason());

    }
}
