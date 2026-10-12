package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardStatus;
import com.gitory.backend.card.domain.ConfirmResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "카드 확정 결과")
public record ConfirmResultResponse(
        @Schema(format = "uuid") String cardId,
        CardStatus status,
        @Schema(description = "확정본 버전 번호") int versionNo,
        @Schema(description = "이 카드를 만드는 데 쓴 후보 id") List<String> usedCandidateIds,
        @Schema(description = "같은 저장소에 아직 카드로 만들지 않은 후보 수") int remainingCandidates,
        @Schema(format = "uuid", description = "카드에 붙은 저장소 id 이고, 없으면 null") String repoId) {

    static ConfirmResultResponse from(ConfirmResult result) {

        return new ConfirmResultResponse(
                result.cardId().toString(),
                result.status(),
                result.versionNo(),
                result.usedCandidateIds().stream().map(UUID::toString).toList(),
                result.remainingCandidates(),
                result.repoId() == null ? null : result.repoId().toString());

    }
}
