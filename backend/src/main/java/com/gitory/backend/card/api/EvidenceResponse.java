package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.RepositoryReference;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record EvidenceResponse(
        StarField field,
        EvidenceType type,
        AuthoredBy authoredBy,
        @Schema(description = "커밋 근거의 앞 7자리 sha 이고, 사용자 근거는 null") String sha,
        @Schema(description = "커밋 근거의 GitHub 커밋 주소이고, 사용자 근거나 저장소가 없는 카드는 null") String url,
        @Schema(description = "커밋 근거의 커밋 메시지 첫 줄이고, 사용자 근거나 메시지가 없으면 null") String snippet,
        @Schema(description = "커밋 근거의 커밋 작성 시각이고, 사용자 근거는 null") Instant committedAt,
        @Schema(description = "커밋 근거의 바뀐 파일 수이고, 사용자 근거나 수집 때 몰랐으면 null") Integer changedFiles,
        @Schema(description = "되묻기 질문에 답해 칸의 글이 된 근거에만 그 질문 번호가 있고 그 밖에는 null") Integer turnNo) {

    private static final int SHORT_SHA_LENGTH = 7;

    // 커밋을 찾지 못하면 링크를 지어내지 않고 sha·url 을 비운다
    static EvidenceResponse commit(StarField field, CommitSummary commit, RepositoryReference repository) {

        if (commit == null) {
            return new EvidenceResponse(field, EvidenceType.COMMIT, AuthoredBy.AI, null, null, null, null, null, null);
        }

        String url = repository == null ? null
                : "https://github.com/" + repository.ownerLogin() + "/" + repository.name() + "/commit/" + commit.sha();

        return new EvidenceResponse(field, EvidenceType.COMMIT, AuthoredBy.AI, shortSha(commit.sha()), url,
                headline(commit.message()), commit.authoredAt(), commit.changedFiles(), null);

    }

    static EvidenceResponse user(StarField field, EvidenceType type, Integer turnNo) {

        return new EvidenceResponse(field, type, AuthoredBy.USER, null, null, null, null, null, turnNo);

    }

    private static String headline(String message) {

        String first = message == null ? "" : message.strip().lines().findFirst().orElse("").strip();
        return first.isEmpty() ? null : first;

    }

    private static String shortSha(String sha) {

        return sha.length() <= SHORT_SHA_LENGTH ? sha : sha.substring(0, SHORT_SHA_LENGTH);

    }
}
