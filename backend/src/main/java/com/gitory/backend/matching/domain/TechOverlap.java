package com.gitory.backend.matching.domain;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공고 하나와 내 카드 기술이 겹친 것을 자리별로 나눈 것이며, 같은 기술은 가장 높은 자리(필수 > 우대 > 팀 기술) 하나로만 센다
 * 겹친 기술 이름은 화면이 '왜 이 공고인지' 로 그대로 보여 주므로 공고에 적힌 순서를 지킨다
 */
public record TechOverlap(List<String> required, int requiredTotal, List<String> preferred, List<String> team) {

    // 필수 기술은 개수가 아니라 채운 비율로 견줘, 필수 기술을 많이 적은 공고가 겹친 개수만으로 앞서지 않게 한다
    static final Comparator<TechOverlap> STRONGER_FIRST = ((Comparator<TechOverlap>) TechOverlap::compareRequiredRatio)
            .thenComparingInt(overlap -> overlap.preferred().size())
            .thenComparingInt(overlap -> overlap.team().size())
            .reversed();

    public static TechOverlap of(JobPosting posting, List<JobPostingCriterion> criteria, Set<String> mine) {

        Set<String> required = techsOf(criteria, CriterionType.REQUIRED);
        Set<String> preferred = techsOf(criteria, CriterionType.PREFERRED);
        preferred.removeAll(required);
        Set<String> team = new LinkedHashSet<>(posting.getTeamTechs());
        team.removeAll(required);
        team.removeAll(preferred);
        return new TechOverlap(matched(required, mine), required.size(), matched(preferred, mine), matched(team, mine));

    }

    private static Set<String> techsOf(List<JobPostingCriterion> criteria, CriterionType type) {

        return criteria.stream()
                .filter(criterion -> criterion.getType() == type)
                .flatMap(criterion -> criterion.getTechs().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

    }

    private static List<String> matched(Set<String> techs, Set<String> mine) {

        return techs.stream().filter(mine::contains).toList();

    }

    public boolean isEmpty() {

        return required.isEmpty() && preferred.isEmpty() && team.isEmpty();

    }

    // 필수 기술이 없는 공고는 비율을 0 으로 둬, 필수 기술을 하나라도 채운 공고보다 뒤에 온다
    private static int compareRequiredRatio(TechOverlap left, TechOverlap right) {

        return Long.compare((long) left.required.size() * right.ratioBase(),
                (long) right.required.size() * left.ratioBase());

    }

    private int ratioBase() {

        return Math.max(requiredTotal, 1);

    }
}
