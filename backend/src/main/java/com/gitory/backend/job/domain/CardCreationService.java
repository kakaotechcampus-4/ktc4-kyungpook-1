package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.CandidateCardSeed;
import com.gitory.backend.card.domain.CandidateCardService;
import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateForCard;
import com.gitory.backend.recommend.domain.CandidateUseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties({JobDraftProperties.class, CardCreationProperties.class})
public class CardCreationService {

    private final CandidateUseService candidates;
    private final CandidateCardService cards;
    private final AnalysisJobRepository jobs;
    private final JobDraftProperties draft;
    private final CardCreationProperties limits;
    private final UserRateLimiter rateLimiter;

    /** 같은 후보를 여러 번 보내면 한 번으로 세고, 형식이 잘못된 id 는 null 로 받아 없는 후보와 같게 다룬다 */
    @Transactional
    public CreatedCards create(Long userId, Long userRepositoryId, List<UUID> candidateIds) {

        List<UUID> distinct = candidateIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(candidateIds));
        if (distinct.isEmpty()) {
            throw new NoCandidatesException();
        }
        // 카드마다 초안 LLM 을 두 번 불러 한 번에 수십 장이면 비용·대기가 커서, 후보를 확인하기 전에 개수부터 막는다
        if (distinct.size() > limits.maxPerRequest()) {
            throw new TooManyCandidatesException(limits.maxPerRequest());
        }

        List<CandidateForCard> used = candidates.useForCards(userId, userRepositoryId, distinct);
        // 카드마다 STAR 초안 LLM 을 부르므로 카드 수만큼 센다. 후보 확인(404·409) 뒤에 세고, 거절되면 후보 상태도 되돌린다
        if (draft.enabled()) {
            rateLimiter.acquire(RateLimitedAction.CARD_DRAFT, userId, used.size());
        }
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
