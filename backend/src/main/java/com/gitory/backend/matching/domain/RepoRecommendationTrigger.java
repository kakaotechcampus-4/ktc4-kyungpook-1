package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.ConfirmedCardChanged;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 레포 추천을 다시 계산할 레포를 표시만 하고, 계산은 배경 작업이 마지막 변경 뒤 QUIET 동안 조용할 때 한 번 한다
 * 화면이 몇 초마다 자동 저장하므로, 바뀔 때마다 계산하면 글을 고치는 내내 AI 를 다시 부르게 된다
 */
@Component
@RequiredArgsConstructor
public class RepoRecommendationTrigger {

    static final Duration QUIET = Duration.ofMinutes(1);

    private final RepoRecommendationRepository recommendations;
    private final Clock clock;

    // 카드 변경이 롤백되면 표시하지 않고, 커밋이 끝난 뒤라 표시는 새 트랜잭션으로 남긴다
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmedCardChanged(ConfirmedCardChanged event) {

        recommendations.markStale(event.userId(), event.userRepositoryId(), Instant.now(clock));

    }

    // 회사 후보를 관심 분야·학력·경력으로 거르므로, 인적사항이 바뀌면 그 사용자가 이미 받은 레포 추천을 모두 다시 계산한다
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void profileChanged(ProfileChanged event) {

        recommendations.markStaleOfUser(event.userId(), Instant.now(clock));

    }

    /** 초안 Job 처럼 변경이 이미 끝난 경우에 불러, 기다리지 않고 다음 배경 작업 차례에 계산하게 한다 */
    public void requestNow(Long userId, Long userRepositoryId) {

        recommendations.markStale(userId, userRepositoryId, Instant.now(clock).minus(QUIET));

    }
}
