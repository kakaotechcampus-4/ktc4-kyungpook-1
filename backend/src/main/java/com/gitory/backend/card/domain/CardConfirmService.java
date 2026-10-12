package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 내 카드를 확정하고, 현재 버전을 그대로 복사한 확정본을 새 버전으로 남긴다
 * 응답을 못 받은 화면이 다시 확정해도 성공으로 보이도록, 이미 확정된 카드는 새 버전 없이 지금 확정 결과를 돌려준다
 */
@Service
@RequiredArgsConstructor
public class CardConfirmService {

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardVersionReader reader;
    private final CardVersionWriter writer;
    private final RepositoryReferenceService repositories;
    private final AuditLog auditLog;

    @Transactional
    public ConfirmResult confirm(UUID publicId, Long userId) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.CONFIRM_CARD);

        if (card.isConfirmed()) {
            return resultOf(card);
        }

        // 문장을 고치는 요청은 모두 카드 행을 먼저 잠그므로, 잠근 뒤 읽는 여기서는 문장과 근거가 한 시점으로 읽힌다
        VersionSlots slots = reader.read(card.getId(), card.getCurrentVersion());
        if (slots.stateOf(StarField.S) == StarFieldState.EMPTY || slots.stateOf(StarField.A) == StarFieldState.EMPTY) {
            throw new CardNotConfirmableException();
        }

        // 확정은 글을 바꾸지 않으므로 확정본의 출처도 현재 버전 것을 그대로 쓴다
        CardVersion current = versions.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion()).orElseThrow();
        CardVersion confirmed = writer.copyToNext(card, current.getVersionNo(), current.getSource());
        confirmed.markConfirmed();
        card.confirm();
        auditLog.ok(userId, AuditAction.CONFIRM_CARD, card.getId());

        return resultOf(card);

    }

    private ConfirmResult resultOf(Card card) {

        UUID repoId = Optional.ofNullable(card.getUserRepositoryId())
                .flatMap(repositories::find)
                .map(RepositoryReference::publicId)
                .orElse(null);

        // 아직 후보가 하나도 만들어지지 않아, 확정한 카드가 쓴 후보도 같은 저장소에 남은 후보도 없다
        return new ConfirmResult(card.getPublicId(), card.getStatus(), card.getCurrentVersion(), List.of(), 0, repoId);

    }
}
