package com.gitory.backend.notice.domain;

import java.util.List;
import java.util.UUID;

public record NoticePayload(UUID repoId, String repoName, Integer cardCount, List<String> cardTitles, UUID jobId,
                            String errorCode, List<NoticePosting> postings, UUID letterId, String letterTitle,
                            UUID questionId, String questionText, UUID cardId, String cardTitle) {

    public static NoticePayload drafted(UUID repoId, String repoName, int cardCount, List<String> cardTitles,
                                        UUID jobId) {

        return new NoticePayload(repoId, repoName, cardCount, cardTitles, jobId, null, List.of(), null, null, null,
                null, null, null);

    }

    public static NoticePayload matched(List<NoticePosting> postings) {

        return new NoticePayload(null, null, null, List.of(), null, null, postings, null, null, null, null, null,
                null);

    }

    public static NoticePayload failed(UUID repoId, String repoName, UUID jobId, String errorCode) {

        return new NoticePayload(repoId, repoName, null, List.of(), jobId, errorCode, List.of(), null, null, null,
                null, null, null);

    }

    public static NoticePayload letterCard(UUID letterId, String letterTitle, UUID questionId, String questionText,
                                           UUID cardId, String cardTitle) {

        return new NoticePayload(null, null, null, List.of(), null, null, List.of(), letterId, letterTitle,
                questionId, questionText, cardId, cardTitle);

    }
}
