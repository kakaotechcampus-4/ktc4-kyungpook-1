package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.InterviewReply;
import com.gitory.backend.card.domain.InterviewReplyOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "AI 가 되묻기 답을 읽은 결과와 그 뒤의 카드")
public record InterviewReplyResponse(
        @Schema(description = "APPLIED 는 AI 가 쓴 문장을 카드에 넣음, ASK_MORE 는 모자란 답이라 같은 칸의 꼬리 질문을 만듦, NOT_USED 는 상관없는 답이라 아무것도 저장하지 않고 질문을 열어 둠, SKIPPED 는 모른다는 답이라 질문을 넘김, UNPOLISHED 는 AI 가 답하지 못하거나 쓸 수 없는 문장을 줘서 아무것도 저장하지 않음")
        InterviewReplyOutcome outcome,
        @Schema(description = "ASK_MORE 와 NOT_USED 일 때 AI 가 쓴 한 줄 이유(60자 이내)이고, 그 밖에는 null", nullable = true)
        String reason,
        @Schema(description = "카드에 넣은 칸의 글로, 물은 칸이 맨 앞이다. APPLIED 가 아니면 빈 배열")
        List<InterviewReplyWrittenResponse> written,
        @Schema(description = "APPLIED 일 때 넣기 전 현재 버전 번호로, 버전 되돌리기 API 에 그대로 보내 넣기 전 글로 돌아간다. 이미 넣은 답이 다시 온 경우와 APPLIED 가 아니면 null",
                nullable = true) Short restoreVersionNo,
        @Schema(description = "이 호출 뒤의 카드") CardResponse card,
        @Schema(description = "ASK_MORE 일 때 새로 만든 꼬리 질문이고, 그 밖에는 null", nullable = true)
        InterviewTurnResponse nextTurn) {

    static InterviewReplyResponse from(InterviewReply reply) {

        return new InterviewReplyResponse(reply.outcome(), reply.reason(),
                reply.written().stream().map(InterviewReplyWrittenResponse::from).toList(), reply.restoreVersionNo(),
                CardResponse.from(reply.card()),
                reply.nextTurn() == null ? null : InterviewTurnResponse.from(reply.nextTurn()));

    }
}
