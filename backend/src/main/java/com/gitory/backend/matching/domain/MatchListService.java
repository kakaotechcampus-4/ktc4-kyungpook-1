package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.agent.port.JobMatchingSearchPort;
import com.gitory.backend.agent.port.MatchingSearchRequest;
import com.gitory.backend.agent.port.MatchingSearchResult;
import com.gitory.backend.agent.port.MatchingSearchUnavailableException;
import com.gitory.backend.agent.port.MatchingStatement;
import com.gitory.backend.card.domain.ConfirmedStatementReader;
import com.gitory.backend.card.domain.ConfirmedStatementSet;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 매칭 목록을 열 때마다 확정 카드 문장과 공고 조건을 AI 검색으로 맞춰 등급을 센다
 * 결과는 저장하지 않고, 검색 호출이 DB 트랜잭션을 붙잡지 않도록 이 서비스는 트랜잭션을 열지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchListService {

    // AI 검색이 한 번에 받는 문장 수와 문장 하나의 글자 수 상한과 같은 값이다
    private static final int MAX_STATEMENTS = 200;
    private static final int MAX_BODY_LENGTH = 4000;

    private static final Comparator<MatchTarget> BY_FIT_THEN_DEADLINE = Comparator
            .comparing(MatchTarget::fit, Comparator.nullsLast(Comparator.<MatchGrade>naturalOrder()))
            .thenComparing(MatchTarget::expiresAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));

    private final JobPostingRepository postings;
    private final ConfirmedStatementReader statements;
    private final JobMatchingSearchPort search;
    private final Clock clock;

    public List<MatchTarget> list(Long userId) {

        List<MatchableCondition> rows = postings.findMatchableConditions(Instant.now(clock));
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<MatchableCondition>> byPosting = rows.stream()
                .collect(Collectors.groupingBy(row -> row.posting().getId(), LinkedHashMap::new, Collectors.toList()));

        ConfirmedStatementSet confirmed = statements.read(userId, MAX_STATEMENTS);
        List<MatchingStatement> sendable = sendable(confirmed);
        Set<Long> metIds = sendable.isEmpty() ? Set.of() : metCriterionIds(userId, sendable, byPosting.size());

        return byPosting.values().stream()
                .map(conditions -> targetOf(conditions, metIds, confirmed.hasConfirmedCard()))
                .sorted(BY_FIT_THEN_DEADLINE)
                .toList();

    }

    // AI 가 빈 글과 4000자를 넘는 글을 400 으로 거절하면 그 사용자의 목록이 카드를 고칠 때까지 계속 실패하므로 보내기 전에 거른다
    private static List<MatchingStatement> sendable(ConfirmedStatementSet confirmed) {

        return confirmed.statements().stream()
                .filter(statement -> !isBlankForAi(statement.body()))
                .map(statement -> new MatchingStatement(statement.statementId(), cut(statement.body())))
                .toList();

    }

    private static boolean isBlankForAi(String body) {

        return body.chars().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '');

    }

    private static String cut(String body) {

        if (body.length() <= MAX_BODY_LENGTH) {
            return body;
        }
        int end = Character.isHighSurrogate(body.charAt(MAX_BODY_LENGTH - 1)) ? MAX_BODY_LENGTH - 1 : MAX_BODY_LENGTH;

        return body.substring(0, end);

    }

    private Set<Long> metCriterionIds(Long userId, List<MatchingStatement> sent, int postingCount) {

        long started = System.nanoTime();
        MatchingSearchResult result;
        try {
            result = search.search(new MatchingSearchRequest(sent));
        } catch (MatchingSearchUnavailableException failure) {
            log.warn("매칭 검색 실패: 사용자 {} {} retryable={}", userId, failure.getErrorCode(), failure.isRetryable());
            throw failure.isRetryable() ? new MatchingAiUnavailableException(failure)
                    : new InvalidMatchingResponseException(failure);
        }
        log.info("매칭 검색: 문장 {}개 · {}ms", sent.size(), Duration.ofNanos(System.nanoTime() - started).toMillis());

        // 색인이 비면 카드가 있는 모든 사용자가 근거 없는 D 로 보이므로 판단하지 못한 것으로 답한다
        if (result.collectionCriteria() == 0) {
            log.warn("매칭 색인이 비어 있다: 목록 대상 공고 {}개", postingCount);
            throw new MatchingAiUnavailableException("AI 매칭 색인이 비어 있다");
        }

        return result.matches().stream().map(CriterionMatch::criterionId).collect(Collectors.toSet());

    }

    // 색인이 늦어 목록에 없는 공고나 등급을 세지 않는 조건, 모르는 id 의 결과는 공고의 조건과 만나지 않아 세지 않는다
    private MatchTarget targetOf(List<MatchableCondition> conditions, Set<Long> metIds, boolean hasConfirmedCard) {

        JobPosting posting = conditions.getFirst().posting();
        MatchCount required = count(conditions, CriterionType.REQUIRED, metIds);
        MatchCount preferred = count(conditions, CriterionType.PREFERRED, metIds);
        MatchGrade fit = hasConfirmedCard ? MatchGrading.fit(required, preferred) : null;

        return new MatchTarget(posting.getPublicId(), posting.getCompanyName(), posting.getTitle(),
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
