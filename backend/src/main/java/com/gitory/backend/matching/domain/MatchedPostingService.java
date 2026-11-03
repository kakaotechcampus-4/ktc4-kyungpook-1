package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** 홈의 '매칭된 공고' 수 — 전체 매칭·레포 추천·공고 상세 맞춰 보기가 함께 쌓는 판단을 세기만 하고 AI 와 호출 한도는 쓰지 않는다 */
@Service
@RequiredArgsConstructor
public class MatchedPostingService {

    private final JobPostingRepository postings;
    private final MatchingCardReader cards;
    private final MatchJudgementService judgements;
    private final Clock clock;

    /** 지금 확정 카드 글로 필수 기술·경험 조건을 하나라도 채운다고 판단된 마감 전 공고 수이며, 공고마다 한 번만 센다 */
    public int count(Long userId) {

        Set<Long> met = judgements.metCriteria(cards.confirmedOf(userId));
        if (met.isEmpty()) {
            return 0;
        }
        return (int) postings.findMatchableConditions(Instant.now(clock)).stream()
                .filter(condition -> condition.type() == CriterionType.REQUIRED && condition.generalType() == null)
                .filter(condition -> met.contains(condition.criterionId()))
                .map(condition -> condition.posting().getId())
                .distinct()
                .count();

    }
}
