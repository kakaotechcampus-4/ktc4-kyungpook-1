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

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

    private final RepositoryOwnershipService repositoryOwnership;
    private final CardRepository cards;
    private final CardVersionWriter versions;
    private final CardDetailService details;
    private final TransactionTemplate transaction;

    /** repositoryPublicId 는 없어도 되고, 있으면 내 저장소여야 하며 아니면 없는 저장소와 똑같이 404 로 답하게 한다 */
    public CardDetail start(Long userId, String title, String period, UUID repositoryPublicId, String idempotencyKey) {

        Long userRepositoryId = ownedRepository(repositoryPublicId, userId);
        String key = checkedKey(idempotencyKey);
        UUID cardId = existing(userId, key)
                .orElseGet(() -> create(userId, userRepositoryId, title, period, key));

        return details.load(cardId, userId);

    }

    private Long ownedRepository(UUID repositoryPublicId, Long userId) {

        if (repositoryPublicId == null) {
            return null;
        }

        return repositoryOwnership.findOwnedRepository(repositoryPublicId, userId, AuditAction.CREATE_CARD)
                .orElseThrow(ConnectedRepositoryNotFoundException::new);

    }

    private static String checkedKey(String idempotencyKey) {

        // 빈 키를 키로 쓰면 빈 키로 온 요청이 모두 처음 만든 카드 하나를 돌려받는다
        if (idempotencyKey == null || CardText.isBlank(idempotencyKey)) {
            return null;
        }

        // 아주 긴 키는 유니크 인덱스 한도에 걸려 저장 때 500 이 된다
        if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new InvalidCardInputException("Idempotency-Key 는 " + IDEMPOTENCY_KEY_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        return idempotencyKey;

    }

    private UUID create(Long userId, Long userRepositoryId, String title, String period, String idempotencyKey) {

        try {
            return transaction.execute(status -> save(userId, userRepositoryId, title, period, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            return existing(userId, idempotencyKey).orElseThrow(() -> e);
        }

    }

    private UUID save(Long userId, Long userRepositoryId, String title, String period, String idempotencyKey) {

        Card card = cards.save(Card.startManual(userId, userRepositoryId, title, period, idempotencyKey));
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
