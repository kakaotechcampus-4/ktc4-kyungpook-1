package com.gitory.backend.notice.api;

import com.gitory.backend.notice.domain.NoticePosting;

import java.time.Instant;

public record NoticePostingResponse(String postingId, String companyName, String title, int requiredMet,
                                    int requiredTotal, Instant expiresAt) {

    static NoticePostingResponse from(NoticePosting posting) {

        return new NoticePostingResponse(posting.postingId().toString(), posting.companyName(), posting.title(),
                posting.requiredMet(), posting.requiredTotal(), posting.expiresAt());

    }
}
