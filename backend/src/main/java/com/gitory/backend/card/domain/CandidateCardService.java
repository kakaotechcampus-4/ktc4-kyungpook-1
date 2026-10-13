package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 후보마다 카드 한 장을 1번 버전과 함께 만들고, 요청 트랜잭션 안에서 후보를 잠근 쪽이 부른다
 * 직접 추가하거나 회상으로 만든 후보의 글은 1번 버전의 과제(T) 칸에 사용자가 쓴 문장으로 넣는다
 * 후보 하나로 살아 있는 카드는 하나뿐이라는 규칙은 DB 유니크가 마지막으로 막고, 걸리면 이미 카드가 있는 후보로 답한다
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CandidateCardService {

    private static final String CANDIDATE_UNIQUE = "uq_card_candidate";

    private final CardRepository cards;
    private final CardVersionWriter versions;
    private final CardStatementRepository statements;

    /** 받은 순서대로 카드를 만들고, 카드 공개 id 도 같은 순서로 돌려준다 */
    public List<UUID> createAll(Long userId, Long userRepositoryId, UUID generationJobId,
                                List<CandidateCardSeed> seeds) {

        return seeds.stream()
                .map(seed -> create(userId, userRepositoryId, generationJobId, seed))
                .toList();

    }

    private UUID create(Long userId, Long userRepositoryId, UUID generationJobId, CandidateCardSeed seed) {

        try {
            Card card = cards.save(Card.startFromCandidate(userId, userRepositoryId, seed.candidateId(),
                    seed.title(), seed.kind(), generationJobId));
            CardVersion first = versions.startFirst(card, VersionSource.USER_EDIT);
            fillTask(card, first, seed.taskText());

            return card.getPublicId();
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause().getMessage().contains(CANDIDATE_UNIQUE)) {
                throw new CandidateAlreadyUsedException();
            }

            throw e;
        }

    }

    // 나머지 칸은 문장 행을 만들지 않아 비어 있다
    private void fillTask(Card card, CardVersion version, String taskText) {

        if (taskText == null || CardText.isBlank(taskText)) {
            return;
        }

        statements.save(CardStatement.userStated(card.getId(), version.getVersionNo(), StarField.T, taskText));

    }
}
