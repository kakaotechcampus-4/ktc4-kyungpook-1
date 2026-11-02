package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.OverallRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileService;
import com.gitory.backend.profile.domain.ProfileView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverallRecommendationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant PROFILE_SAVED = Instant.parse("2026-10-30T00:00:00Z");
    private static final Long USER = 1L;
    private static final MatchingCard CARD = new MatchingCard(11L, 7L, "결제 API", null, null,
            "Spring 으로 결제 API 를 만들었다", null, List.of(CardTech.SPRING));

    private final OverallRecommendationRepository recommendations = mock(OverallRecommendationRepository.class);
    private final MatchingCardReader cards = mock(MatchingCardReader.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final CompanyDirectory companies = mock(CompanyDirectory.class);
    private final OverallRecommendationQueryService queries = new OverallRecommendationQueryService(recommendations,
            cards, profiles, postings, companies, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {

        when(cards.confirmedOf(USER)).thenReturn(List.of(CARD));
        when(profiles.of(USER)).thenReturn(profile(PROFILE_SAVED));
        when(postings.findAllById(anyList())).thenReturn(List.of());
        when(companies.of(any(), any())).thenReturn(new MatchCompany("toss", "https://static.toss.im/logo.png"));

    }

    @Test
    @DisplayName("한 번도 요청하지 않은 사용자는 상태 없이 빈 추천이다")
    void showsNothingBeforeFirstRequest() {

        when(recommendations.findByUserId(USER)).thenReturn(Optional.empty());

        assertThat(queries.of(USER)).isEqualTo(new OverallRecommendationView(null, null, false, null, List.of()));

    }

    @Test
    @DisplayName("계산 중이면 확인한 공고 수를 싣고, 시작한 지 10분이 지난 계산은 멈춘 것이라 실패로 보여 준다")
    void showsProgressWhileComputingAndStuckComputingAsFailed() {

        when(recommendations.findByUserId(USER))
                .thenReturn(Optional.of(saved(OverallRecommendationStatus.COMPUTING, NOW.minus(Duration.ofMinutes(10))
                        .plusSeconds(1), null)));
        OverallRecommendationView computing = queries.of(USER);
        assertThat(computing.status()).isEqualTo(OverallRecommendationStatus.COMPUTING);
        assertThat(computing.progress()).isEqualTo(new OverallRecommendationView.Progress(6, 15));

        when(recommendations.findByUserId(USER))
                .thenReturn(Optional.of(saved(OverallRecommendationStatus.COMPUTING, NOW.minus(Duration.ofMinutes(10)),
                        null)));
        OverallRecommendationView stuck = queries.of(USER);
        assertThat(stuck.status()).isEqualTo(OverallRecommendationStatus.FAILED);
        assertThat(stuck.progress()).isNull();

    }

    @Test
    @DisplayName("계산 뒤 확정 카드 글이나 인적사항 저장 시각이 바뀌었을 때만 stale 이고, 계산 중에는 stale 이 아니다")
    void marksStaleOnlyWhenCardsOrProfileChangedAfterCompute() {

        String computedWith = InputFingerprint.of(List.of(CARD), PROFILE_SAVED);
        when(recommendations.findByUserId(USER))
                .thenReturn(Optional.of(saved(OverallRecommendationStatus.READY, NOW, computedWith)));
        assertThat(queries.of(USER).stale()).isFalse();

        when(cards.confirmedOf(USER)).thenReturn(List.of(new MatchingCard(11L, 7L, "결제 API", null, null,
                "Spring 으로 결제 API 를 다시 만들었다", null, List.of(CardTech.SPRING))));
        assertThat(queries.of(USER).stale()).isTrue();

        when(cards.confirmedOf(USER)).thenReturn(List.of(CARD));
        when(profiles.of(USER)).thenReturn(profile(PROFILE_SAVED.plusSeconds(1)));
        assertThat(queries.of(USER).stale()).isTrue();

        when(recommendations.findByUserId(USER))
                .thenReturn(Optional.of(saved(OverallRecommendationStatus.COMPUTING, NOW, computedWith)));
        assertThat(queries.of(USER).stale()).isFalse();

    }

    @Test
    @DisplayName("저장한 기업은 로고와 대표 공고를 붙여 보여 주고, 그 뒤 마감된 공고는 마감으로, 사라진 공고의 기업은 빼고 보여 준다")
    void showsSavedCompaniesWithClosedPostingsAndWithoutRemovedOnes() {

        JobPosting open = posting(21L, NOW.plusSeconds(86_400));
        JobPosting expired = posting(22L, NOW);
        when(postings.findAllById(anyList())).thenReturn(List.of(open, expired));
        OverallRecommendation saved = saved(OverallRecommendationStatus.READY, NOW, null);
        ReflectionTestUtils.setField(saved, "companies", List.of(
                new OverallCompany("toss", "토스", 21L, 3, 4, 1, 2),
                new OverallCompany("kakao", "카카오", 22L, 1, 2, 0, 0),
                new OverallCompany("line", "라인", 23L, 1, 1, 0, 0)));
        when(recommendations.findByUserId(USER)).thenReturn(Optional.of(saved));

        assertThat(queries.of(USER).companies())
                .extracting(company -> company.companyName(), company -> company.logoUrl(),
                        company -> company.posting().closed(), company -> company.requiredTotal())
                .containsExactly(tuple("토스", "https://static.toss.im/logo.png", false, 4),
                        tuple("카카오", "https://static.toss.im/logo.png", true, 2));

    }

    private static OverallRecommendation saved(OverallRecommendationStatus status, Instant requestedAt,
                                               String fingerprint) {

        OverallRecommendation saved = BeanUtils.instantiateClass(OverallRecommendation.class);
        ReflectionTestUtils.setField(saved, "userId", USER);
        ReflectionTestUtils.setField(saved, "status", status);
        ReflectionTestUtils.setField(saved, "companies", List.of());
        ReflectionTestUtils.setField(saved, "fingerprint", fingerprint);
        ReflectionTestUtils.setField(saved, "progressDone", 6);
        ReflectionTestUtils.setField(saved, "progressTotal", 15);
        ReflectionTestUtils.setField(saved, "requestedAt", requestedAt);
        return saved;

    }

    private static JobPosting posting(Long id, Instant expiresAt) {

        JobPosting posting = JobPosting.discovered("toss", String.valueOf(id), "토스", "서버 개발자",
                "https://jobs.example.com/" + id, null, expiresAt, NOW.minusSeconds(86_400));
        ReflectionTestUtils.setField(posting, "id", id);
        return posting;

    }

    private static ProfileView profile(Instant savedAt) {

        return new ProfileView(List.of(), List.of(), List.of(), List.of(), List.of(), savedAt);

    }
}
