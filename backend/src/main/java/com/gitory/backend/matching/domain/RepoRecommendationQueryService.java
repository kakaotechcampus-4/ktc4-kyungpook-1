package com.gitory.backend.matching.domain;

import com.gitory.backend.audit.domain.AuditAction;
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
    private final JobPostingRepository postings;
    private final CompanyDirectory companies;
    private final Clock clock;

    /** 남의 레포나 없는 레포면 RecommendationRepositoryNotFoundException, 아직 계산한 적이 없으면 status 가 null 이다 */
    @Transactional(readOnly = true)
    public RepoRecommendationView of(UUID repoPublicId, Long userId) {

        Long repoId = ownership.findOwnedRepository(repoPublicId, userId, AuditAction.VIEW_REPO)
                .orElseThrow(RecommendationRepositoryNotFoundException::new);
        return recommendations.findByUserRepositoryId(repoId)
                .map(this::view)
                .orElse(new RepoRecommendationView(null, null, List.of()));

    }

    private RepoRecommendationView view(RepoRecommendation recommendation) {

        Instant now = Instant.now(clock);
        Map<Long, JobPosting> byId = postings.findAllById(recommendation.getCompanies().stream()
                        .map(RecommendedCompany::postingId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(JobPosting::getId, Function.identity()));
        List<RepoRecommendationView.Company> shown = recommendation.getCompanies().stream()
                .map(company -> company(company, byId.get(company.postingId()), now))
                .filter(Objects::nonNull)
                .toList();
        return new RepoRecommendationView(recommendation.getStatus(), recommendation.getComputedAt(), shown);

    }

    private RepoRecommendationView.Company company(RecommendedCompany company, JobPosting posting, Instant now) {

        if (posting == null) {
            return null;
        }
        return new RepoRecommendationView.Company(company.companyId(), company.companyName(),
                companies.of(posting.getCompanyKey(), posting.getCompanyName()).logoUrl(),
                new RepoRecommendationView.Posting(posting.getPublicId(), posting.getTitle(), posting.getExpiresAt(),
                        posting.isClosedAt(now)),
                company.techs(), company.requiredMet(), company.preferredMet());

    }
}
