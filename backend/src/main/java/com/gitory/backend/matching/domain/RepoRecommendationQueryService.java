package com.gitory.backend.matching.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 레포 화면이 읽는 저장된 레포 추천이며, 화면을 열 때 계산하지 않는다
 * 저장한 뒤 마감되거나 사라진 공고는 다시 계산하지 않고 마감으로 보여 준다
 */
@Service
@RequiredArgsConstructor
public class RepoRecommendationQueryService {

    private final RepositoryOwnershipService ownership;
    private final RepoRecommendationRepository recommendations;
    private final RepoRecommendationLiveLog liveLog;
    private final RepoRecommendationTrigger trigger;
    private final MatchingCardReader cards;
    private final JobPostingRepository postings;
    private final CompanyDirectory companies;
    private final Clock clock;

    /**
     * 남의 레포나 없는 레포면 RecommendationRepositoryNotFoundException, 계산한 적이 없고 카드도 없으면 status 가 null 이다
     * 계산한 적이 없는데 카드가 있으면 계산을 예약만 하고 COMPUTING 과 queued 로 답하며, 여는 요청 안에서 계산하지는 않는다
     * queued 는 다시 계산이 예약돼 아직 시작하지 않았다는 뜻이고, live 는 메모리에 남은 진행이 없으면 null 이다
     */
    @Transactional
    public RepoRecommendationView of(UUID repoPublicId, Long userId) {

        return viewOf(ownedRepository(repoPublicId, userId), userId);

    }

    /** 본인 레포의 추천을 다음 배경 작업 차례에 바로 다시 계산하도록 예약하고 조회와 같은 모양으로 답하며, 이때 queued 는 true 다 */
    @Transactional
    public RepoRecommendationView refresh(UUID repoPublicId, Long userId) {

        Long repoId = ownedRepository(repoPublicId, userId);
        trigger.requestNow(userId, repoId);

        return viewOf(repoId, userId);

    }

    private Long ownedRepository(UUID repoPublicId, Long userId) {

        return ownership.findOwnedRepository(repoPublicId, userId, AuditAction.VIEW_REPO)
                .orElseThrow(RecommendationRepositoryNotFoundException::new);

    }

    private RepoRecommendationView viewOf(Long repoId, Long userId) {

        Optional<RepoRecommendation> saved = recommendations.findByUserRepositoryId(repoId);
        // 계산이 멈추면 진행을 먼저 지운 뒤 실패로 저장하므로, 저장본을 먼저 읽어야 FAILED 인데 끝나지 않은 진행이 보이지 않는다
        RepoRecommendationLive live = liveLog.of(repoId).orElse(null);
        return saved.map(recommendation -> view(recommendation, live))
                .orElseGet(() -> notComputed(userId, repoId, live));

    }

    // 카드를 작성 중으로만 둔 레포처럼 추천 줄이 한 번도 만들어지지 않은 레포는 카드가 바뀌기 전까지 계산할 계기가 없어서
    // 카드가 있으면 조회할 때 계산을 예약한다
    private RepoRecommendationView notComputed(Long userId, Long repoId, RepoRecommendationLive live) {

        if (!cards.existsOf(userId, repoId)) {
            return new RepoRecommendationView(null, null, List.of(), false, live);
        }
        trigger.requestNow(userId, repoId);
        return new RepoRecommendationView(RepoRecommendationStatus.COMPUTING, null, List.of(), true, live);

    }

    private RepoRecommendationView view(RepoRecommendation recommendation, RepoRecommendationLive live) {

        Instant now = Instant.now(clock);
        Map<Long, JobPosting> byId = postings.findAllById(recommendation.getCompanies().stream()
                        .map(RecommendedCompany::postingId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(JobPosting::getId, Function.identity()));
        Set<String> previous = recommendation.getPreviousCompanies() == null ? Set.of()
                : recommendation.getPreviousCompanies().stream().map(RecommendedCompany::companyId).collect(Collectors.toSet());
        List<RepoRecommendationView.Company> shown = recommendation.getCompanies().stream()
                .map(company -> company(company, byId.get(company.postingId()), now, previous))
                .filter(Objects::nonNull)
                .toList();
        return new RepoRecommendationView(recommendation.getStatus(), recommendation.getComputedAt(), shown,
                recommendation.getStaleSince() != null, live);

    }

    private RepoRecommendationView.Company company(RecommendedCompany company, JobPosting posting, Instant now,
                                                    Set<String> previous) {

        if (posting == null) {
            return null;
        }
        return new RepoRecommendationView.Company(company.companyId(), company.companyName(),
                companies.of(posting.getCompanyKey(), posting.getCompanyName()).logoUrl(),
                new RepoRecommendationView.Posting(posting.getPublicId(), posting.getTitle(), posting.getExpiresAt(),
                        posting.isClosedAt(now)),
                company.techs(), company.requiredMet(), company.preferredMet(),
                !previous.isEmpty() && !previous.contains(company.companyId()));

    }
}
