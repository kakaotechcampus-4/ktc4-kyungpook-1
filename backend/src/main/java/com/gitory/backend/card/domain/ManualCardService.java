package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * 직접 쓰는 카드를 빈 1번 버전으로 시작하고, 같은 요청 키로 다시 오면 그 키로 만든 카드를 돌려준다
 * 같은 키의 동시 요청은 DB 유니크에 막힌 쪽이 실패한 트랜잭션 밖에서 저장된 카드를 다시 찾아야 하므로, 메서드 전체가 아니라 저장만 트랜잭션으로 감싼다
 */
@Service
@RequiredArgsConstructor
public class ManualCardService {

    private final RepositoryOwnershipService repositoryOwnership;
    private final ActivityService activities;
    private final CardRepository cards;
    private final CardVersionWriter versions;
    private final CardDetailService details;
    private final TransactionTemplate transaction;

    /**
     * 저장소와 활동은 둘 다 없어도 되고, 있으면 내 것이어야 하며 아니면 없는 것과 똑같이 404 로 답하게 한다
     * 카드는 저장소와 활동 중 하나에만 묶이므로 둘 다 오면 400 으로 답하게 한다
     */
    public CardDetail start(Long userId, String title, String period, UUID repositoryPublicId, UUID activityPublicId,
                            String idempotencyKey) {

        if (repositoryPublicId != null && activityPublicId != null) {
            throw new InvalidCardInputException("저장소와 활동 중 하나만 고를 수 있습니다.");
        }

        Long userRepositoryId = ownedRepository(repositoryPublicId, userId);
        Long activityId = activityPublicId == null ? null : activities.ownedActivityId(activityPublicId, userId);
        String key = IdempotencyKey.checked(idempotencyKey);
        UUID cardId = existing(userId, key)
                .orElseGet(() -> create(userId, userRepositoryId, activityId, title, period, key));

        return details.load(cardId, userId);

    }

    private Long ownedRepository(UUID repositoryPublicId, Long userId) {

        if (repositoryPublicId == null) {
            return null;
        }

        return repositoryOwnership.findOwnedRepository(repositoryPublicId, userId, AuditAction.CREATE_CARD)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);

    }

    private UUID create(Long userId, Long userRepositoryId, Long activityId, String title, String period,
                        String idempotencyKey) {

        try {
            return transaction.execute(status -> save(userId, userRepositoryId, activityId, title, period,
                    idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            return existing(userId, idempotencyKey).orElseThrow(() -> e);
        }

    }

    private UUID save(Long userId, Long userRepositoryId, Long activityId, String title, String period,
                      String idempotencyKey) {

        if (activityId != null) {
            activities.requireExists(activityId);
        }
        Card card = cards.save(Card.startManual(userId, userRepositoryId, activityId, title, period, idempotencyKey));
        versions.startFirst(card, VersionSource.USER_EDIT);

        return card.getPublicId();

    }

    private Optional<UUID> existing(Long userId, String idempotencyKey) {

        // 키 없이 찾으면 Spring Data 가 IS NULL 조건으로 바꿔 키 없이 만든 카드들이 걸린다
        if (idempotencyKey == null) {
            return Optional.empty();
        }

        return cards.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(Card::getPublicId);

    }
}
