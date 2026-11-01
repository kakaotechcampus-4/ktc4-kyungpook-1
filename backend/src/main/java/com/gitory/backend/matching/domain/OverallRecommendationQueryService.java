package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.OverallRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공고 페이지가 읽는 저장된 전체 추천이며, 화면을 열 때 계산하지 않는다
 * 계산 뒤 확정 카드나 인적사항이 바뀌었으면 stale 로 알리기만 하고, 다시 계산은 사용자가 누를 때만 한다
 */
@Service
@RequiredArgsConstructor
public class OverallRecommendationQueryService {

    private final OverallRecommendationRepository recommendations;
    private final MatchingCardReader cards;
    private final ProfileService profiles;
    private final JobPostingRepository postings;
    private final CompanyDirectory companies;
    private final Clock clock;

    /** 계산한 적이 없으면 status 가 null 이고, 시작한 지 오래된 COMPUTING 은 실패로 보여 다시 누를 수 있게 한다 */
    @Transactional(readOnly = true)
    public OverallRecommendationView of(Long userId) {

        return recommendations.findByUserId(userId)
                .map(saved -> view(userId, saved))
                .orElse(new OverallRecommendationView(null, null, false, null, List.of()));

    }

    private OverallRecommendationView view(Long userId, OverallRecommendation saved) {

        Instant now = Instant.now(clock);
        OverallRecommendationStatus status = saved.isStuck(now) ? OverallRecommendationStatus.FAILED
                : saved.getStatus();
        boolean computing = status == OverallRecommendationStatus.COMPUTING;
        boolean stale = !computing && saved.getFingerprint() != null && !saved.getFingerprint()
                .equals(InputFingerprint.of(cards.confirmedOf(userId), profiles.of(userId).savedAt()));
        Map<Long, JobPosting> byId = postings.findAllById(saved.getCompanies().stream()
                        .map(OverallCompany::postingId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(JobPosting::getId, Function.identity()));
        List<OverallRecommendationView.Company> shown = saved.getCompanies().stream()
                .map(company -> company(company, byId.get(company.postingId()), now))
                .filter(Objects::nonNull)
                .toList();
        return new OverallRecommendationView(status, saved.getComputedAt(), stale,
                computing ? new OverallRecommendationView.Progress(saved.getProgressDone(), saved.getProgressTotal())
                        : null,
                shown);

    }

    private OverallRecommendationView.Company company(OverallCompany company, JobPosting posting, Instant now) {

        if (posting == null) {
            return null;
        }
        return new OverallRecommendationView.Company(company.companyId(), company.companyName(),
                companies.of(posting.getCompanyKey(), posting.getCompanyName()).logoUrl(),
                new RepoRecommendationView.Posting(posting.getPublicId(), posting.getTitle(), posting.getExpiresAt(),
                        posting.isClosedAt(now)),
                company.requiredMet(), company.requiredTotal(), company.preferredMet(), company.preferredTotal());

    }
}
