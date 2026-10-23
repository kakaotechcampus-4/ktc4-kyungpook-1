package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchListServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Test
    @DisplayName("같은 등급 안에서는 회사마다 한 건씩 돌아가며 놓는다")
    void alternatesCompaniesWithinSameFit() {

        List<MatchTarget> sorted = List.of(
                target("토스", "토스1", MatchGrade.A), target("토스", "토스2", MatchGrade.A),
                target("토스", "토스3", MatchGrade.A), target("카카오", "카카오1", MatchGrade.A),
                target("네이버", "네이버1", MatchGrade.A));

        assertThat(roles(MatchListService.mixCompanies(sorted, NOW)))
                .containsExactly("토스1", "카카오1", "네이버1", "토스2", "토스3");

    }

    @Test
    @DisplayName("등급을 넘어서는 섞지 않아 낮은 등급 공고가 높은 등급보다 위로 가지 않는다")
    void keepsHigherFitAboveLowerFit() {

        List<MatchTarget> sorted = List.of(
                target("토스", "토스1", MatchGrade.A), target("토스", "토스2", MatchGrade.A),
                target("카카오", "카카오1", MatchGrade.B), target("토스", "토스3", MatchGrade.B));

        assertThat(roles(MatchListService.mixCompanies(sorted, NOW)))
                .containsExactly("토스1", "토스2", "카카오1", "토스3");

    }

    @Test
    @DisplayName("회사 순서와 같은 회사 안의 공고 순서는 들어온 정렬 순서를 따른다")
    void keepsIncomingOrderForCompaniesAndTheirPostings() {

        List<MatchTarget> sorted = List.of(
                target("카카오", "카카오1", MatchGrade.A), target("토스", "토스1", MatchGrade.A),
                target("카카오", "카카오2", MatchGrade.A), target("토스", "토스2", MatchGrade.A),
                target("카카오", "카카오3", MatchGrade.A));

        assertThat(roles(MatchListService.mixCompanies(sorted, NOW)))
                .containsExactly("카카오1", "토스1", "카카오2", "토스2", "카카오3");

    }

    @Test
    @DisplayName("공고를 빼거나 겹치지 않고 들어온 공고를 모두 그대로 돌려준다")
    void returnsEveryPostingOnce() {

        List<MatchTarget> sorted = List.of(
                target("토스", "토스1", MatchGrade.A), target("카카오", "카카오1", MatchGrade.B),
                target("토스", "토스2", MatchGrade.B), target("토스", "토스3", MatchGrade.C),
                target("라인", "라인1", null));

        assertThat(MatchListService.mixCompanies(sorted, NOW)).containsExactlyInAnyOrderElementsOf(sorted);

    }

    @Test
    @DisplayName("등급이 비어 있는 공고는 맨 뒤에 두고 그 안에서도 회사를 섞는다")
    void mixesPostingsWithoutFitAtTheEnd() {

        List<MatchTarget> sorted = List.of(
                target("카카오", "카카오1", MatchGrade.D), target("토스", "토스1", null),
                target("토스", "토스2", null), target("라인", "라인1", null));

        assertThat(roles(MatchListService.mixCompanies(sorted, NOW)))
                .containsExactly("카카오1", "토스1", "라인1", "토스2");

    }

    @Test
    @DisplayName("마감이 7일 안에 남은 공고는 섞지 않고 등급마다 맨 앞에 마감순으로 둔다")
    void putsUrgentPostingsFirstWithinSameFit() {

        List<MatchTarget> sorted = List.of(
                target("토스", "토스1", MatchGrade.A, NOW.plus(Duration.ofDays(1))),
                target("토스", "토스2", MatchGrade.A, NOW.plus(Duration.ofDays(7))),
                target("카카오", "카카오1", MatchGrade.A, NOW.plus(Duration.ofDays(8))),
                target("토스", "토스3", MatchGrade.A, null),
                target("네이버", "네이버1", MatchGrade.B, NOW.plus(Duration.ofDays(2))));

        assertThat(roles(MatchListService.mixCompanies(sorted, NOW)))
                .containsExactly("토스1", "토스2", "카카오1", "토스3", "네이버1");

    }

    private static MatchTarget target(String company, String role, MatchGrade fit) {

        return target(company, role, fit, null);

    }

    private static MatchTarget target(String company, String role, MatchGrade fit, Instant expiresAt) {

        return new MatchTarget(UUID.randomUUID(), company, role, null, fit, null, null, null, null, null, null,
                expiresAt);

    }

    private static List<String> roles(List<MatchTarget> targets) {

        return targets.stream().map(MatchTarget::role).toList();

    }
}
