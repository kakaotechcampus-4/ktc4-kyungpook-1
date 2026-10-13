package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.CandidateCardSeed;
import com.gitory.backend.card.domain.CandidateCardService;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateForCard;
import com.gitory.backend.recommend.domain.CandidateUseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 고른 후보를 카드로 쓴 상태로 바꾸고, 후보마다 카드와 이를 묶는 카드 초안 Job 하나를 한 트랜잭션으로 만든다
 * 하나라도 못 쓰는 후보가 있으면 카드·Job·후보 상태 어느 것도 바꾸지 않는다
 */
@Service
@RequiredArgsConstructor
public class CardCreationService {

    private final CandidateUseService candidates;
    private final CandidateCardService cards;
    private final AnalysisJobRepository jobs;

    /** 같은 후보를 여러 번 보내면 한 번으로 세고, 형식이 잘못된 id 는 null 로 받아 없는 후보와 같게 다룬다 */
    @Transactional
    public CreatedCards create(Long userId, Long userRepositoryId, List<UUID> candidateIds) {

        List<UUID> distinct = candidateIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(candidateIds));
        if (distinct.isEmpty()) {
            throw new NoCandidatesException();
        }

        List<CandidateForCard> used = candidates.useForCards(userId, userRepositoryId, distinct);
        // 화면이 요청 키를 보내지 않고 같은 사용자의 키는 겹치면 안 되어 요청마다 새로 만든다
        AnalysisJob job = jobs.save(AnalysisJob.enqueueDraft(userId, userRepositoryId, UUID.randomUUID().toString()));
        List<UUID> cardIds = cards.createAll(userId, userRepositoryId, job.getPublicId(), used.stream()
                .map(candidate -> new CandidateCardSeed(candidate.id(), candidate.title(), kindOf(candidate),
                        candidate.taskText()))
                .toList());

        return new CreatedCards(job.getPublicId(), cardIds);

    }

    // 포함된 커밋이 없는 후보는 코드 근거가 없어 정성 카드로 시작한다
    private static CardKind kindOf(CandidateForCard candidate) {

        return candidate.hasIncludedCommit() ? CardKind.TECH : CardKind.QUALITATIVE;

    }
}
