package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.CardDraftRetryTargets;
import com.gitory.backend.card.domain.DraftRetryTarget;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * AI 초안을 채우지 못한 카드 한 장으로 새 초안 Job 을 만들어 다시 채운다
 * 카드 확인·한도 세기·Job 저장·카드를 새 Job 에 잇기를 한 트랜잭션으로 묶어, 거절되면 아무것도 남지 않는다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(JobDraftProperties.class)
public class CardDraftRetryService {

    private final CardDraftRetryTargets targets;
    private final AnalysisJobRepository jobs;
    private final JobDraftProperties draft;
    private final UserRateLimiter rateLimiter;

    @Transactional
    public UUID retry(UUID cardPublicId, Long userId) {

        DraftRetryTarget target = targets.lockRetryable(cardPublicId, userId);
        // 카드를 만들 때처럼 초안 LLM 을 부를 때만 카드 한 장만큼 센다
        if (draft.enabled()) {
            rateLimiter.acquire(RateLimitedAction.CARD_DRAFT, userId, 1);
        }
        AnalysisJob job = jobs.save(AnalysisJob.enqueueDraft(userId, target.userRepositoryId(), UUID.randomUUID().toString()));
        targets.restart(target.cardId(), job.getPublicId());

        return job.getPublicId();

    }
}
