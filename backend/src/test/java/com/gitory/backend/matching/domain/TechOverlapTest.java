package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TechOverlapTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("같은 기술이 여러 자리에 있으면 가장 높은 자리(필수 > 우대 > 팀 기술) 하나로만 센다")
    void countsEachTechOnceAtItsHighestPlace() {

        JobPosting posting = posting(List.of(CardTech.JAVA, CardTech.REDIS, CardTech.DOCKER));
        List<JobPostingCriterion> criteria = List.of(
                criterion(CriterionType.REQUIRED, CardTech.JAVA, CardTech.SPRING),
                criterion(CriterionType.PREFERRED, CardTech.SPRING, CardTech.REDIS));

        TechOverlap overlap = TechOverlap.of(posting, criteria, names(CardTech.JAVA, CardTech.SPRING, CardTech.REDIS,
                CardTech.DOCKER));

        assertThat(overlap.required()).containsExactly("JAVA", "SPRING");
        assertThat(overlap.requiredTotal()).isEqualTo(2);
        assertThat(overlap.preferred()).containsExactly("REDIS");
        assertThat(overlap.team()).containsExactly("DOCKER");

    }

    @Test
    @DisplayName("필수 기술을 채운 비율, 겹친 우대 기술 수, 겹친 팀 기술 수 순서로 앞에 둔다")
    void ordersByRequiredRatioThenPreferredThenTeam() {

        TechOverlap allOfOne = new TechOverlap(List.of("JAVA"), 1, List.of(), List.of());
        TechOverlap threeOfFour = new TechOverlap(List.of("JAVA", "SPRING", "JPA"), 4, List.of("REDIS"), List.of());
        TechOverlap halfWithPreferred = new TechOverlap(List.of("JAVA"), 2, List.of("REDIS", "KAFKA"), List.of());
        TechOverlap halfWithTeam = new TechOverlap(List.of("JAVA"), 2, List.of("REDIS", "KAFKA"), List.of("DOCKER"));

        List<TechOverlap> sorted = new ArrayList<>(List.of(halfWithPreferred, threeOfFour, halfWithTeam, allOfOne));
        sorted.sort(TechOverlap.STRONGER_FIRST);

        assertThat(sorted).containsExactly(allOfOne, threeOfFour, halfWithTeam, halfWithPreferred);

    }

    @Test
    @DisplayName("필수 기술이 없는 공고는 필수 기술을 하나라도 채운 공고보다 뒤에 온다")
    void putsPostingWithoutRequiredTechsBelowOneMeetingAny() {

        TechOverlap noRequired = new TechOverlap(List.of(), 0, List.of("REDIS", "KAFKA", "SPARK"), List.of("DOCKER"));
        TechOverlap oneOfFive = new TechOverlap(List.of("JAVA"), 5, List.of(), List.of());

        List<TechOverlap> sorted = new ArrayList<>(List.of(noRequired, oneOfFive));
        sorted.sort(TechOverlap.STRONGER_FIRST);

        assertThat(sorted).containsExactly(oneOfFive, noRequired);

    }

    private static Set<String> names(CardTech... techs) {

        Set<String> names = new HashSet<>();
        for (CardTech tech : techs) {
            names.add(tech.name());
        }
        return names;

    }

    private static JobPosting posting(List<CardTech> teamTechs) {

        JobPosting posting = JobPosting.discovered("acme", "1", "에이크미", "백엔드 개발자",
                "https://jobs.example.com/1", null, null, NOW);
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.ENTRY, null, null, teamTechs, 2,
                JobPostingContentStatus.READY, "a".repeat(64), NOW);
        return posting;

    }

    private static JobPostingCriterion criterion(CriterionType type, CardTech... techs) {

        return JobPostingCriterion.extracted(1L, type, 1, "조건", "조건", null, List.of(techs), null, false);

    }
}
