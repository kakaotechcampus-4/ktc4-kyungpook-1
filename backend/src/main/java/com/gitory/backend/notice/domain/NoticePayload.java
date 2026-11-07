package com.gitory.backend.notice.domain;

import java.util.List;
import java.util.UUID;

public record NoticePayload(UUID repoId, String repoName, Integer cardCount, List<String> cardTitles, UUID jobId,
                            String errorCode, List<NoticePosting> postings) {

    public static NoticePayload drafted(UUID repoId, String repoName, int cardCount, List<String> cardTitles,
                                        UUID jobId) {

        return new NoticePayload(repoId, repoName, cardCount, cardTitles, jobId, null, List.of());

    }

    public static NoticePayload matched(List<NoticePosting> postings) {

        return new NoticePayload(null, null, null, List.of(), null, null, postings);

    }

    public static NoticePayload failed(UUID repoId, String repoName, UUID jobId, String errorCode) {

        return new NoticePayload(repoId, repoName, null, List.of(), jobId, errorCode, List.of());

    }
}
