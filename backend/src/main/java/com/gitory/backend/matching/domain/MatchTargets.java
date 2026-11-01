package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.MatchableCondition;

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
                posting.getExpiresAt());

    }

    private static MatchCount count(List<MatchableCondition> conditions, CriterionType type, Set<Long> metIds) {

        List<MatchableCondition> ofType = conditions.stream().filter(condition -> condition.type() == type).toList();
        int met = (int) ofType.stream().filter(condition -> metIds.contains(condition.criterionId())).count();

        return new MatchCount(ofType.size(), met);

    }
}
