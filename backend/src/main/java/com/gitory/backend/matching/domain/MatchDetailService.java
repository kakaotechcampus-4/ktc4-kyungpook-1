package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.card.domain.ConfirmedStatement;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import com.gitory.backend.matching.infra.PostingCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 공고 하나의 조건마다 어떤 카드 문장이 근거인지 보여 주며, 목록과 같은 검색 한 번에서 그 공고의 조건만 고른다
 * 검색 호출이 DB 트랜잭션을 붙잡지 않도록 트랜잭션을 열지 않는다
 */
@Service
@RequiredArgsConstructor
public class MatchDetailService {

    // 같은 종류 안에서는 원문 순서(seq)이고, 종류는 enum 선언 순서(필수 → 우대 → 담당 업무)다
    private static final Comparator<JobPostingCriterion> CONDITION_ORDER = Comparator
            .comparing(JobPostingCriterion::getType).thenComparing(JobPostingCriterion::getSeq);

    private static final Comparator<CriterionMatch> CLOSEST_FIRST = Comparator
            .comparingDouble(CriterionMatch::distance).thenComparingLong(CriterionMatch::statementId);

    private final JobPostingRepository postings;
    private final MatchEvidenceSearch evidenceSearch;
    private final CompanyDirectory companies;
    private final Clock clock;

    public MatchDetail detail(UUID postingId, Long userId) {

        List<PostingCondition> rows = postings.findMatchableDetail(postingId, Instant.now(clock));
        if (rows.isEmpty()) {
            throw new MatchPostingNotFoundException();
        }

        MatchEvidence evidence = evidenceSearch.find(userId);
        Map<Long, ConfirmedStatement> statements = evidence.confirmed().statements().stream()
                .collect(Collectors.toMap(ConfirmedStatement::statementId, statement -> statement));
        Map<Long, List<CriterionMatch>> matchesByCriterion = evidence.matches().stream()
                .collect(Collectors.groupingBy(CriterionMatch::criterionId));

        List<MatchableCondition> graded = rows.stream()
                .map(row -> new MatchableCondition(row.posting(), row.criterion().getId(), row.criterion().getType()))
                .toList();
        Set<Long> metIds = matchesByCriterion.keySet();
        MatchTarget target = MatchTargets.of(graded, metIds, evidence.confirmed().hasConfirmedCard(), companies);

        List<MatchCondition> conditions = rows.stream()
                .map(PostingCondition::criterion)
                .sorted(CONDITION_ORDER)
                .map(criterion -> conditionOf(criterion, matchesByCriterion.getOrDefault(criterion.getId(), List.of()), statements))
                .toList();

        return new MatchDetail(target, rows.getFirst().posting().getExperienceQuote(), conditions);

    }

    private static MatchCondition conditionOf(JobPostingCriterion criterion, List<CriterionMatch> matches,
                                              Map<Long, ConfirmedStatement> statements) {

        List<MatchSupport> supports = matches.stream()
                .sorted(CLOSEST_FIRST)
                .map(match -> statements.get(match.statementId()))
                .map(statement -> new MatchSupport(statement.cardPublicId(), statement.cardTitle(),
                        statement.cardKind(), statement.field(), statement.body()))
                .toList();

        return new MatchCondition(criterion.getPublicId(), criterion.getType(), criterion.getText(),
                criterion.getQuote(), supports);

    }
}
