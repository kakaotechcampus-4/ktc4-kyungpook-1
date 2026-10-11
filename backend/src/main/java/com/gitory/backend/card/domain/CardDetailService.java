package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 내 카드 한 장을 현재 버전의 네 칸과 함께 읽는다
 * 임시 저장이 문장을 지우고 다시 넣는 사이에도 문장과 근거를 한 시점으로 읽도록 REPEATABLE READ 로 읽는다
 */
@Service
@RequiredArgsConstructor
public class CardDetailService {

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardVersionReader reader;
    private final RepositoryReferenceService repositories;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CardDetail load(UUID publicId, Long userId) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.VIEW_CARD);
        CardVersion current = versions.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion()).orElseThrow();
        RepositoryReference repository = Optional.ofNullable(card.getUserRepositoryId())
                .flatMap(repositories::find)
                .orElse(null);

        return new CardDetail(card.getPublicId(), card.getCardType(), card.getStatus(), card.getTitle(),
                card.getPeriod(), repository, current.getSource(), current.getUpdatedAt(),
                reader.read(card.getId(), card.getCurrentVersion()), card.getMaskRules(), card.getConfirmedAt(),
                card.getCreatedAt());

    }
}
