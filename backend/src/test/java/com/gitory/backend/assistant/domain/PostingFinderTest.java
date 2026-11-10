package com.gitory.backend.assistant.domain;

import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PostingFinderTest {

    private static final Instant TODAY = Instant.parse("2026-11-06T00:00:00Z");

    @Test
    @DisplayName("회사 이름은 공백과 대소문자를 무시한 부분 일치로 거른다")
    void filtersCompanyIgnoringSpacesAndCase() {

        MatchTarget toss = target("토스", "서버 개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);
        MatchTarget tossBank = target("Toss Bank", "서버 개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);
        MatchTarget kakaoPay = target("카카오 페이", "서버 개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);
        MatchTarget kakao = target("카카오", "서버 개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);
        List<MatchTarget> all = List.of(toss, tossBank, kakaoPay, kakao);

        assertThat(PostingFinder.find(all, "토스", List.of()).top()).containsExactly(toss);
        assertThat(PostingFinder.find(all, " TOSS bank ", List.of()).top()).containsExactly(tossBank);
        assertThat(PostingFinder.find(all, "카카오페이", List.of()).top()).containsExactly(kakaoPay);
        assertThat(PostingFinder.find(all, "카카오", List.of()).top()).containsExactly(kakaoPay, kakao);
        assertThat(PostingFinder.find(all, "라인", List.of()).total()).isZero();

    }

    @Test
    @DisplayName("회사가 없으면 낱말이 하나라도 맞는 공고만 남기고, 많이 맞은 것과 마감이 빠른 것을 앞에 둔다")
    void ranksKeywordMatchesByCountThenDeadline() {

        MatchTarget late = target("A", "개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, at(9));
        MatchTarget soon = target("B", "서버 개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, at(3));
        MatchTarget undated = target("C", "개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);
        MatchTarget single = target("D", "백엔드 개발자", JobCategory.ETC, ExperienceLevel.EXPERIENCED, at(1));
        MatchTarget none = target("E", "디자이너", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));

        PostingFinder.Found found = PostingFinder.find(List.of(none, single, undated, late, soon), null,
                List.of("백엔드", "신입"));

        assertThat(found.top()).containsExactly(soon, late, undated, single);
        assertThat(found.total()).isEqualTo(4);

    }

    @Test
    @DisplayName("제목이나 기술에 낱말이 들어 있으면 맞은 것으로 센다")
    void countsKeywordsContainedInTitleOrTechs() {

        MatchTarget inTitle = target("A", "Spring 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, null);
        MatchTarget inTechs = target("B", "개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, null,
                List.of("Kotlin", "Spring Boot"));
        MatchTarget miss = target("C", "개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, null,
                List.of("Django"));

        PostingFinder.Found found = PostingFinder.find(List.of(miss, inTechs, inTitle), null, List.of("spring"));

        assertThat(found.top()).containsExactly(inTechs, inTitle);

    }

    @ParameterizedTest
    @CsvSource({"백엔드, true", "Backend, true", "서버, true", "백엔, false", "신입, true", "신, false", "경력, false",
            "프론트, false"})
    @DisplayName("분야와 경력 낱말은 표의 말과 같을 때만 맞는다")
    void tableWordsMatchOnlyWhenEqual(String keyword, boolean matches) {

        MatchTarget target = target("A", "개발자", JobCategory.BACKEND, ExperienceLevel.ENTRY, null);

        assertThat(PostingFinder.find(List.of(target), null, List.of(keyword)).total()).isEqualTo(matches ? 1 : 0);

    }

    @Test
    @DisplayName("회사와 낱말을 함께 말하면 그 회사 공고 중 낱말이 맞은 것만 남기고 전체 건수도 그 수로 준다")
    void narrowsCompanyPostingsToKeywordMatches() {

        MatchTarget plain = target("토스", "개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(5));
        MatchTarget backend = target("토스", "백엔드 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(9));
        MatchTarget soonButOff = target("토스", "디자이너", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));
        MatchTarget otherCompany = target("당근", "백엔드 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));

        PostingFinder.Found found = PostingFinder.find(List.of(plain, backend, soonButOff, otherCompany), "토스",
                List.of("백엔드"));

        assertThat(found.top()).containsExactly(backend);
        assertThat(found.total()).isEqualTo(1);

    }

    @Test
    @DisplayName("회사와 낱말을 함께 말했는데 그 회사 공고 중 맞은 것이 없으면 그 회사 공고를 모두 남긴다")
    void keepsAllCompanyPostingsWhenNoneMatchKeywords() {

        MatchTarget late = target("토스", "개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(9));
        MatchTarget soon = target("토스", "디자이너", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));
        MatchTarget otherCompany = target("당근", "백엔드 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));

        PostingFinder.Found found = PostingFinder.find(List.of(late, soon, otherCompany), "토스", List.of("백엔드"));

        assertThat(found.top()).containsExactly(soon, late);
        assertThat(found.total()).isEqualTo(2);

    }

    @Test
    @DisplayName("앞의 다섯 건에는 같은 회사의 같은 제목을 한 번만 담고 마감이 빠른 것을 남기며 전체 건수는 그대로 센다")
    void listsSameCompanyAndRoleOnlyOnce() {

        MatchTarget first = target("토스", "Server 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(1));
        MatchTarget duplicate = target("토스", "Server개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(2));
        MatchTarget upperCase = target("토 스", "SERVER 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(3));
        MatchTarget otherRole = target("토스", "디자이너", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(4));
        MatchTarget otherCompany = target("당근", "Server 개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, at(5));

        PostingFinder.Found found = PostingFinder.find(List.of(otherCompany, otherRole, duplicate, upperCase, first),
                null, List.of());

        assertThat(found.top()).containsExactly(first, otherRole, otherCompany);
        assertThat(found.total()).isEqualTo(5);

    }

    @Test
    @DisplayName("다섯 건까지만 주고 전체 건수는 따로 준다")
    void givesFiveAndReportsTotalSeparately() {

        List<MatchTarget> seven = IntStream.rangeClosed(1, 7)
                .mapToObj(number -> target("회사" + number, "개발자", JobCategory.ETC, ExperienceLevel.UNSPECIFIED, null))
                .toList();

        PostingFinder.Found found = PostingFinder.find(seven, null, List.of());

        assertThat(found.top()).containsExactlyElementsOf(seven.subList(0, 5));
        assertThat(found.total()).isEqualTo(7);

    }

    private static Instant at(int days) {

        return TODAY.plus(days, ChronoUnit.DAYS);

    }

    private static MatchTarget target(String company, String role, JobCategory category, ExperienceLevel level,
                                      Instant expiresAt) {

        return target(company, role, category, level, expiresAt, List.of());

    }

    private static MatchTarget target(String company, String role, JobCategory category, ExperienceLevel level,
                                      Instant expiresAt, List<String> techs) {

        return new MatchTarget(UUID.randomUUID(), company, company, null, role, category, null, null, null, level,
                null, null, null, expiresAt, techs, null);

    }
}
