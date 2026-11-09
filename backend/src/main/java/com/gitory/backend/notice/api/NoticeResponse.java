package com.gitory.backend.notice.api;

import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticeView;

import java.time.Instant;
import java.util.List;

public record NoticeResponse(String id, NoticeKind kind, Instant createdAt, Instant readAt, String repoId,
                             String repoName, Integer cardCount, List<String> cardTitles, String jobId,
                             String errorCode, List<NoticePostingResponse> postings) {

    static NoticeResponse from(NoticeView view) {

        NoticePayload payload = view.payload();

        return new NoticeResponse(view.id().toString(), view.kind(), view.createdAt(), view.readAt(),
                payload.repoId() == null ? null : payload.repoId().toString(), payload.repoName(),
                payload.cardCount(), payload.cardTitles() == null ? List.of() : payload.cardTitles(),
                payload.jobId() == null ? null : payload.jobId().toString(), payload.errorCode(), postingsOf(payload));

    }

    // postings 가 없는 종류의 알림은 저장된 값에 그 칸이 비어 있다
    private static List<NoticePostingResponse> postingsOf(NoticePayload payload) {

        return payload.postings() == null ? List.of()
                : payload.postings().stream().map(NoticePostingResponse::from).toList();

    }
}
