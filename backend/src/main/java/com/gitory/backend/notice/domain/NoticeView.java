package com.gitory.backend.notice.domain;

import java.time.Instant;
import java.util.UUID;

public record NoticeView(UUID id, NoticeKind kind, Instant createdAt, Instant readAt, NoticePayload payload) {

    static NoticeView from(Notice notice) {

        return new NoticeView(notice.getPublicId(), notice.getKind(), notice.getCreatedAt(), notice.getReadAt(),
                notice.getPayload());

    }
}
