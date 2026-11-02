package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.MatchableCondition;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 한 공고의 조건과 맞은 조건 id 로 목록 한 줄을 만들며, 목록과 상세가 같은 등급 규칙을 쓰게 한다 */
public final class MatchTargets {

    private MatchTargets() {
    }

    // 담당 업무 조건은 종류로 거르고, 색인이 늦어 목록에 없는 공고나 모르는 id 의 결과는 이 공고의 조건과 만나지 않아 세지 않는다
    public static MatchTarget of(List<MatchableCondition> conditions, Set<Long> metIds, boolean hasConfirmedCard,
                                 CompanyDirectory companies) {

        JobPosting posting = conditions.getFirst().posting();
        MatchCount required = count(conditions, CriterionType.REQUIRED, metIds);
        MatchCount preferred = count(conditions, CriterionType.PREFERRED, metIds);
        MatchGrade fit = hasConfirmedCard ? MatchGrading.fit(required, preferred) : null;
        MatchCompany company = companies.of(posting.getCompanyKey(), posting.getCompanyName());

        return new MatchTarget(posting.getPublicId(), posting.getCompanyName(), company.id(), company.logoUrl(),
                posting.getTitle(),
                posting.getCategory(), fit, required, preferred, posting.getExperienceLevel(),
                posting.getExperienceMinYears(), posting.getSourceUrl(), posting.getLastSeenAt(),
                posting.getExpiresAt(), techsOf(conditions), educationOf(conditions));

    }

    // 필수·우대 조건을 조건 순서대로 훑어 처음 나온 순서로 한 번씩 둔다(상세 행에는 담당 업무 조건도 섞여 와서 종류로 거른다)
    static List<String> techsOf(List<MatchableCondition> conditions) {

        return conditions.stream()
                .filter(condition -> condition.type() == CriterionType.REQUIRED || condition.type() == CriterionType.PREFERRED)
                .flatMap(condition -> condition.techs().stream())
                .distinct()
                .toList();

    }

    /**
     * 상세처럼 일반 조건 EDUCATION 중 수준을 읽은 것만 견줄 수 있는 학력으로 보고, 목록에는 필수 학력 중 가장 낮은 수준을 둔다
     * 같은 수준에 예외('또는 그에 준하는 경력') 없는 조건이 하나라도 있으면 예외가 아니며, 그런 조건이 없으면 null 이다
     */
    static MatchEducation educationOf(List<MatchableCondition> conditions) {

        return conditions.stream()
                .filter(condition -> condition.type() == CriterionType.REQUIRED)
                .filter(condition -> condition.generalType() == GeneralConditionType.EDUCATION)
                .filter(condition -> condition.educationLevel() != null)
                .min(Comparator.comparing(MatchableCondition::educationLevel)
                        .thenComparing(MatchableCondition::educationExempt))
                .map(condition -> new MatchEducation(condition.educationLevel(), condition.educationExempt()))
                .orElse(null);

    }

    private static MatchCount count(List<MatchableCondition> conditions, CriterionType type, Set<Long> metIds) {

        List<MatchableCondition> ofType = conditions.stream().filter(condition -> condition.type() == type).toList();
        int met = (int) ofType.stream().filter(condition -> metIds.contains(condition.criterionId())).count();

        return new MatchCount(ofType.size(), met);

    }
}
