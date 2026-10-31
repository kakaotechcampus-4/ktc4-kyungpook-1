package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.matching.domain.CompanyShortlistService.CandidatePosting;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.PostingCondition;
import com.gitory.backend.profile.domain.ProfileService;
import com.gitory.backend.profile.domain.ProfileView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyShortlistServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant SOON = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    @DisplayName("넘겨받은 카드 기술과 겹친 기술이 하나도 없는 공고는 후보에 넣지 않는다")
    void dropsPostingsWithoutAnyOverlap() {

        JobPosting java = posting("toss", "토스", null);
        JobPosting react = posting("kakao", "카카오", null);
        JobPostingRepository postings = mock(JobPostingRepository.class);
        when(postings.findShortlistConditions(any())).thenReturn(List.of(
                new PostingCondition(java, required(CardTech.JAVA)),
                new PostingCondition(react, required(CardTech.REACT))));

        List<ShortlistedCompany> companies = service(postings).shortlist(1L, Set.of(CardTech.JAVA), Set.of(), 10);

        assertThat(companies).extracting(ShortlistedCompany::companyId).containsExactly("toss");

    }

    @Test
    @DisplayName("회사는 가장 잘 맞는 공고 하나의 점수로 순위를 매기고 그 공고를 대표 공고로 둔다")
    void ranksCompaniesByTheirBestPosting() {

        CandidatePosting tossWeak = candidate("toss", posting("toss", "토스", null), overlap(1, 4));
        CandidatePosting tossBest = candidate("toss", posting("toss", "토스", null), overlap(2, 2));
        CandidatePosting kakao = candidate("kakao", posting("kakao", "카카오", null), overlap(2, 3));

        List<ShortlistedCompany> ranked = CompanyShortlistService.rank(List.of(tossWeak, kakao, tossBest), 10);

        assertThat(ranked).extracting(ShortlistedCompany::companyId).containsExactly("toss", "kakao");
        assertThat(ranked.getFirst().representative()).isSameAs(tossBest.posting());

    }

    @Test
    @DisplayName("대표 공고 점수가 같으면 필수 기술을 채운 공고가 많은 회사, 마감이 가까운 회사, 회사 id 순으로 둔다")
    void breaksTiesByPostingsWithRequiredTechThenDeadlineThenId() {

        CandidatePosting lineOnlyBest = candidate("line", posting("line", "라인", LATER), overlap(1, 1));
        CandidatePosting naverBest = candidate("naver", posting("naver", "네이버", LATER), overlap(1, 1));
        CandidatePosting naverSecond = candidate("naver", posting("naver", "네이버", LATER), overlap(1, 3));
        CandidatePosting kakaoSoon = candidate("kakao", posting("kakao", "카카오", SOON), overlap(1, 1));
        CandidatePosting coupang = candidate("coupang", posting("coupang", "쿠팡", LATER), overlap(1, 1));

        List<ShortlistedCompany> ranked = CompanyShortlistService.rank(
                List.of(lineOnlyBest, naverBest, naverSecond, kakaoSoon, coupang), 10);

        assertThat(ranked).extracting(ShortlistedCompany::companyId)
                .containsExactly("naver", "kakao", "coupang", "line");
        assertThat(ranked.getFirst().postingsWithRequiredTech()).isEqualTo(2);

    }

    @Test
    @DisplayName("부르는 쪽이 정한 수만큼만 회사를 돌려준다")
    void returnsAtMostTheRequestedNumberOfCompanies() {

        List<CandidatePosting> candidates = List.of(
                candidate("a", posting("a", "가", null), overlap(3, 3)),
                candidate("b", posting("b", "나", null), overlap(2, 3)),
                candidate("c", posting("c", "다", null), overlap(1, 3)));

        assertThat(CompanyShortlistService.rank(candidates, 2)).extracting(ShortlistedCompany::companyId)
                .containsExactly("a", "b");

    }

    private static CompanyShortlistService service(JobPostingRepository postings) {

        ProfileService profiles = mock(ProfileService.class);
        when(profiles.of(any())).thenReturn(new ProfileView(List.of(), List.of(), List.of(), List.of(), null));
        CompanyDirectory companies = mock(CompanyDirectory.class);
        when(companies.of(anyString(), anyString()))
                .thenAnswer(call -> new MatchCompany(call.getArgument(0), null));
        return new CompanyShortlistService(postings, profiles, companies, Clock.fixed(NOW, ZoneOffset.UTC));

    }

    private static CandidatePosting candidate(String companyId, JobPosting posting, TechOverlap overlap) {

        return new CandidatePosting(companyId, posting, overlap);

    }

    // 필수 기술 total 개 중 matched 개를 채운 겹침으로, 비율만 견주는 시험에서 이름은 상관없다
    private static TechOverlap overlap(int matched, int total) {

        List<String> required = List.of("JAVA", "SPRING", "JPA", "REDIS").subList(0, matched);
        return new TechOverlap(required, total, List.of(), List.of());

    }

    private static JobPosting posting(String companyKey, String companyName, Instant expiresAt) {

        JobPosting posting = JobPosting.discovered(companyKey, companyKey + "-1", companyName, "백엔드 개발자",
                "https://jobs.example.com/" + companyKey, null, expiresAt, NOW);
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.ENTRY, null, null, List.<CardTech>of(), 2,
                JobPostingContentStatus.READY, "a".repeat(64), NOW);
        return posting;

    }

    private static JobPostingCriterion required(CardTech tech) {

        return JobPostingCriterion.extracted(1L, CriterionType.REQUIRED, 1, "조건", "조건", null, List.of(tech), null,
                false);

    }
}
