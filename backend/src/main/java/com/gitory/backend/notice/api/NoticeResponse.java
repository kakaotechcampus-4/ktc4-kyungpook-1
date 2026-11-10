package com.gitory.backend.notice.api;

import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticeView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NoticeResponse(String id, NoticeKind kind, Instant createdAt, Instant readAt, String repoId,
                             String repoName, Integer cardCount, List<String> cardTitles, String jobId,
                             String errorCode, List<NoticePostingResponse> postings, String letterId,
                             String letterTitle, String questionId, String questionText, String cardId,
                             String cardTitle) {

    static NoticeResponse from(NoticeView view) {

        NoticePayload payload = view.payload();

        return new NoticeResponse(view.id().toString(), view.kind(), view.createdAt(), view.readAt(),
                payload.repoId() == null ? null : payload.repoId().toString(), payload.repoName(),
                payload.cardCount(), payload.cardTitles() == null ? List.of() : payload.cardTitles(),
                payload.jobId() == null ? null : payload.jobId().toString(), payload.errorCode(), postingsOf(payload),
                idOf(payload.letterId()), payload.letterTitle(), idOf(payload.questionId()), payload.questionText(),
                idOf(payload.cardId()), payload.cardTitle());

    }

    // postings 가 없는 종류의 알림은 저장된 값에 그 칸이 비어 있다
    private static List<NoticePostingResponse> postingsOf(NoticePayload payload) {

        return payload.postings() == null ? List.of()
                : payload.postings().stream().map(NoticePostingResponse::from).toList();

    }

    private static String idOf(UUID id) {

        return id == null ? null : id.toString();

    }
}
