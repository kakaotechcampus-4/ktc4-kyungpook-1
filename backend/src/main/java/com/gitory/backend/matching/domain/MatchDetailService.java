package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardLabel;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import com.gitory.backend.matching.infra.PostingCondition;
import com.gitory.backend.profile.domain.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 공고 하나의 조건마다 판단을 붙인 상세로, 열 때는 저장된 AI 판단만 읽고 '이 공고와 맞춰 보기' 를 누를 때만 AI 에게 묻는다
 * 기술·경험 조건은 레포·전체 추천과 같은 (카드, 조건) 판단을 다시 써서, 추천에서 들어온 공고와 상세의 판단이 같다
 */
@Service
@RequiredArgsConstructor
public class MatchDetailService {

    // 인적사항의 달은 한국 달력 기준이다
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 같은 종류 안에서는 원문 순서(seq)이고, 종류는 enum 선언 순서(필수 → 우대 → 담당 업무)다
    private static final Comparator<JobPostingCriterion> CONDITION_ORDER = Comparator
            .comparing(JobPostingCriterion::getType).thenComparing(JobPostingCriterion::getSeq);

    private final JobPostingRepository postings;
    private final MatchingCardReader cards;
    private final MatchJudgementService judgements;
    private final ProfileService profiles;
    private final RepositoryReferenceService repositories;
    private final CompanyDirectory companies;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;

    public MatchDetail detail(UUID postingId, Long userId) {

        List<PostingCondition> rows = rowsOf(postingId);
        return detail(rows, cards.confirmedOf(userId), userId);

    }

    /**
     * 이 공고의 기술·경험 조건을 지금 확정 카드로 AI 에게 묻고 새 상세를 돌려주며, 이미 판단한 (카드, 조건) 짝은 다시 묻지 않는다
     * 물을 짝이 남았을 때만 호출 한도를 세고, AI 가 답하지 못하면 MatchingAiUnavailableException 이다
     */
    public MatchDetail judge(UUID postingId, Long userId) {

        List<PostingCondition> rows = rowsOf(postingId);
        List<MatchingCard> mine = cards.confirmedOf(userId);
        List<JobPostingCriterion> skill = skillConditions(rows);
        boolean unjudged = judgements.stored(skill, mine).values().stream().anyMatch(stored -> !stored.complete());
        if (unjudged) {
            rateLimiter.acquire(RateLimitedAction.MATCH_JUDGEMENT, userId, 1);
            if (judgements.judge(rows.getFirst().posting(), skill, mine).isEmpty()) {
                throw new MatchingAiUnavailableException("공고 조건 판단을 AI 에게서 받지 못했다");
            }
        }
        return detail(rows, mine, userId);

    }

    private List<PostingCondition> rowsOf(UUID postingId) {

        List<PostingCondition> rows = postings.findMatchableDetail(postingId, Instant.now(clock));
        if (rows.isEmpty()) {
            throw new MatchPostingNotFoundException();
        }
        return rows;

    }

    private MatchDetail detail(List<PostingCondition> rows, List<MatchingCard> mine, Long userId) {

        JobPosting posting = rows.getFirst().posting();
        List<JobPostingCriterion> criteria = rows.stream()
                .map(PostingCondition::criterion)
                .sorted(CONDITION_ORDER)
                .toList();
        Map<Long, StoredJudgement> judged = judgements.stored(skillConditions(rows), mine);
        Applicant applicant = Applicant.of(profiles.of(userId), Set.of(),
                YearMonth.from(Instant.now(clock).atZone(SEOUL)));
        Map<Long, MatchingCardLabel> labels = cards.labelsOf(judged.values().stream()
                .flatMap(stored -> stored.metBy().stream())
                .map(JudgedSupport::cardId)
                .collect(Collectors.toSet()));
        Map<Long, RepositoryReference> repos = repositories.findAll(labels.values().stream()
                .map(MatchingCardLabel::userRepositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        List<MatchCondition> conditions = criteria.stream()
                .map(criterion -> condition(criterion, judged.get(criterion.getId()), applicant, labels, repos))
                .toList();
        boolean needsJudgement = judged.values().stream().anyMatch(stored -> !stored.complete());
        Set<Long> metIds = criteria.stream()
                .filter(criterion -> statusOf(criterion, judged.get(criterion.getId()), applicant)
                        == MatchConditionStatus.MET)
                .map(JobPostingCriterion::getId)
                .collect(Collectors.toSet());
        List<MatchableCondition> graded = rows.stream()
                .map(row -> MatchableCondition.of(row.posting(), row.criterion()))
                .toList();

        // 등급은 목록처럼 화면에서 뺐으므로 fit 은 늘 null 이다
        return new MatchDetail(MatchTargets.of(graded, metIds, false, companies), posting.getExperienceQuote(),
                applicant.careerOf(posting), needsJudgement, needsJudgement ? null : summaryOf(criteria, judged),
                conditions);

    }

    private static MatchCondition condition(JobPostingCriterion criterion, StoredJudgement judged, Applicant applicant,
                                            Map<Long, MatchingCardLabel> labels, Map<Long, RepositoryReference> repos) {

        List<MatchMetBy> metBy = judged == null ? List.of() : judged.metBy().stream()
                .filter(support -> labels.containsKey(support.cardId()))
                .map(support -> metBy(support, labels.get(support.cardId()), repos))
                .toList();
        return new MatchCondition(criterion.getPublicId(), criterion.getType(), criterion.getText(),
                criterion.getQuote(), criterion.getGeneralType(), statusOf(criterion, judged, applicant), metBy);

    }

    private static MatchMetBy metBy(JudgedSupport support, MatchingCardLabel label,
                                    Map<Long, RepositoryReference> repos) {

        RepositoryReference repo = label.userRepositoryId() == null ? null : repos.get(label.userRepositoryId());
        return new MatchMetBy(label.publicId(), label.title(), label.kind(), repo == null ? null : repo.publicId(),
                support.reason());

    }

    // 담당 업무는 판단하지 않고, 학력만 인적사항과 견주며, 병역·어학·자격증·지역 같은 일반 조건은 사용자가 직접 확인한다
    private static MatchConditionStatus statusOf(JobPostingCriterion criterion, StoredJudgement judged,
                                                 Applicant applicant) {

        if (criterion.getType() == CriterionType.RESPONSIBILITY) {
            return null;
        }
        if (criterion.getGeneralType() == GeneralConditionType.EDUCATION) {
            return applicant.educationOf(criterion);
        }
        if (criterion.getGeneralType() != null) {
            return MatchConditionStatus.CHECK;
        }
        if (!judged.metBy().isEmpty()) {
            return MatchConditionStatus.MET;
        }
        return judged.complete() ? MatchConditionStatus.NOT_MET : MatchConditionStatus.UNJUDGED;

    }

    // '필수 N개 중 M개' 는 자소서에서 모자란 경험을 물을 기술·경험 필수 조건만 센다
    private static MatchSummary summaryOf(List<JobPostingCriterion> criteria, Map<Long, StoredJudgement> judged) {

        List<JobPostingCriterion> required = criteria.stream()
                .filter(criterion -> criterion.getType() == CriterionType.REQUIRED)
                .filter(criterion -> judged.containsKey(criterion.getId()))
                .toList();
        int met = (int) required.stream()
                .filter(criterion -> !judged.get(criterion.getId()).metBy().isEmpty())
                .count();
        return new MatchSummary(met, required.size());

    }

    private static List<JobPostingCriterion> skillConditions(List<PostingCondition> rows) {

        return rows.stream()
                .map(PostingCondition::criterion)
                .filter(criterion -> criterion.getType() == CriterionType.REQUIRED
                        || criterion.getType() == CriterionType.PREFERRED)
                .filter(criterion -> criterion.getGeneralType() == null)
                .toList();

    }
}
