package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.agent.port.JobMatchingSearchPort;
import com.gitory.backend.agent.port.MatchingSearchRequest;
import com.gitory.backend.agent.port.MatchingSearchResult;
import com.gitory.backend.agent.port.MatchingSearchUnavailableException;
import com.gitory.backend.agent.port.MatchingStatement;
import com.gitory.backend.card.domain.ConfirmedStatementReader;
import com.gitory.backend.card.domain.ConfirmedStatementSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 사용자의 확정 카드 문장으로 AI 검색을 한 번 불러 공고 조건과 맞는 문장을 찾는다
 * 목록과 상세가 같은 검색을 쓰고, 검색 호출이 DB 트랜잭션을 붙잡지 않도록 트랜잭션을 열지 않는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEvidenceSearch {

    // AI 검색이 한 번에 받는 문장 수와 문장 하나의 글자 수 상한과 같은 값이다
    private static final int MAX_STATEMENTS = 200;
    private static final int MAX_BODY_LENGTH = 4000;

    private final ConfirmedStatementReader statements;
    private final JobMatchingSearchPort search;

    public MatchEvidence find(Long userId) {

        ConfirmedStatementSet confirmed = statements.read(userId, MAX_STATEMENTS);
        List<MatchingStatement> sendable = sendable(confirmed);
        if (sendable.isEmpty()) {
            return new MatchEvidence(confirmed, List.of());
        }

        return new MatchEvidence(confirmed, matchesOf(userId, sendable));

    }

    // AI 가 빈 글과 4000자를 넘는 글을 400 으로 거절하면 그 사용자의 매칭이 카드를 고칠 때까지 계속 실패하므로 보내기 전에 거른다
    private static List<MatchingStatement> sendable(ConfirmedStatementSet confirmed) {

        return confirmed.statements().stream()
                .filter(statement -> !isBlankForAi(statement.body()))
                .map(statement -> new MatchingStatement(statement.statementId(), cut(statement.body())))
                .toList();

    }

    private static boolean isBlankForAi(String body) {

        return body.chars().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85);

    }

    private static String cut(String body) {

        if (body.length() <= MAX_BODY_LENGTH) {
            return body;
        }
        int end = Character.isHighSurrogate(body.charAt(MAX_BODY_LENGTH - 1)) ? MAX_BODY_LENGTH - 1 : MAX_BODY_LENGTH;

        return body.substring(0, end);

    }

    private List<CriterionMatch> matchesOf(Long userId, List<MatchingStatement> sent) {

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
            log.warn("매칭 색인이 비어 있다");
            throw new MatchingAiUnavailableException("AI 매칭 색인이 비어 있다");
        }

        return result.matches();

    }
}
