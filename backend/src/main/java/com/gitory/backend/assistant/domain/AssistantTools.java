package com.gitory.backend.assistant.domain;

import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.coverletter.domain.CoverLetterPostingNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.coverletter.domain.NoConfirmedCardsException;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.MatchCondition;
import com.gitory.backend.matching.domain.MatchConditionStatus;
import com.gitory.backend.matching.domain.MatchDetail;
import com.gitory.backend.matching.domain.MatchDetailService;
import com.gitory.backend.matching.domain.MatchListService;
import com.gitory.backend.matching.domain.MatchPostingNotFoundException;
import com.gitory.backend.matching.domain.MatchSummary;
import com.gitory.backend.matching.domain.MatchTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * AI 가 고른 도구 넷을 이미 있는 공고 찾기·맞춰 보기·자소서·레포 정리 기능으로 실행하고 결과를 걸음에 남길 값으로 바꾼다
 * 도구가 던진 예외는 실행을 끝내지 않고 걸음 실패 코드로 바꿔, AI 가 다음을 정하게 한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTools {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 아래 길이 상한은 AI 홈 한 줄 명령 계약과 자소서 지원 직무 상한이라 넘으면 AI 요청이나 저장이 실패한다
    private static final int COMPANY_MAX_LENGTH = 255;
    private static final int TITLE_MAX_LENGTH = 500;
    private static final int JOB_ROLE_MAX_LENGTH = 100;
    private static final int CONDITION_MAX_LENGTH = 80;
    private static final int CONDITIONS_LIMIT = 5;

    private final MatchListService matchList;
    private final MatchDetailService matchDetails;
    private final CoverLetterService coverLetters;
    private final MeCardStatsService cardStats;
    private final RepoAnalysisPort repoAnalysis;
    private final Clock clock;

    /** seq 는 이 걸음의 번호로, 같은 걸음을 다시 실행해도 자소서가 둘 생기지 않게 자소서 요청 키에 쓴다 */
    public ToolResult execute(Long userId, int seq, ToolCall call) {

        try {
            return switch (call.tool()) {
                case FIND_POSTINGS -> findPostings(userId, call.find());
                case MATCH_POSTING -> matchPosting(userId, call.posting());
                case START_COVER_LETTER -> startCoverLetter(userId, seq, call);
                case START_REPO_ANALYSIS -> startRepoAnalysis(userId, call.repo());
            };
        } catch (RuntimeException failure) {
            ToolError error = errorOf(failure);
            if (error == ToolError.FAILED) {
                log.warn("홈 한 줄 명령의 {} 도구가 실패했다", call.tool(), failure);
            }

            return ToolResult.failed(error);
        }

    }

    /** 확정 카드가 없으면 맞춰 본 공고가 있을 수 없어, 저장된 판단을 읽지 않고 필수 조건 수를 비운다 */
    private ToolResult findPostings(Long userId, RunItem.Find find) {

        PostingFinder.Found found = PostingFinder.find(matchList.list(null), find.company(), find.keywords());
        boolean hasCards = cardStats.confirmedCards(userId) > 0;
        List<RunItem.Posting> postings = found.top().stream()
                .map(target -> posting(target, hasCards ? savedSummary(target, userId) : null))
                .toList();

        return ToolResult.found(postings, found.total());

    }

    private ToolResult matchPosting(Long userId, RunItem.Posting posting) {

        if (cardStats.confirmedCards(userId) == 0) {
            return ToolResult.failed(ToolError.NO_CONFIRMED_CARDS);
        }

        MatchDetail saved = matchDetails.detail(posting.postingId(), userId);
        MatchDetail detail = saved.needsJudgement() ? matchDetails.judge(posting.postingId(), userId) : saved;
        MatchSummary summary = detail.summary();
        if (summary == null) {
            return ToolResult.failed(ToolError.FAILED);
        }

        List<MatchCondition> required = detail.conditions().stream()
                .filter(condition -> condition.type() == CriterionType.REQUIRED && condition.generalType() == null)
                .toList();
        RunItem.Match match = new RunItem.Match(summary.requiredMet(), summary.requiredTotal(),
                texts(required, true), texts(required, false));

        return ToolResult.matched(posting.withRequired(summary.requiredMet(), summary.requiredTotal()), match);

    }

    /** 지원 이유는 비워 보내고, 공고가 있으면 공고 제목을 지원 직무로 쓴다 */
    private ToolResult startCoverLetter(Long userId, int seq, ToolCall call) {

        String jobRole = call.posting() != null ? clip(call.posting().title(), JOB_ROLE_MAX_LENGTH) : call.jobRole();
        if (jobRole == null || jobRole.isBlank()) {
            return ToolResult.failed(ToolError.FAILED);
        }

        UUID postingId = call.posting() == null ? null : call.posting().postingId();
        CoverLetterView letter = coverLetters.create(userId, jobRole, null, List.of(), call.questions(), postingId,
                "assistant-" + call.runId() + "-" + seq);

        return ToolResult.coverLetter(call.questions().size(), letter.id());

    }

    private ToolResult startRepoAnalysis(Long userId, RepoLabel repo) {

        return ToolResult.repoAnalysis(repo.id(), repoAnalysis.start(userId, repo.id()));

    }

    // 이미 맞춰 본 공고만 값이 있어, 아직 판단하지 않은 공고는 비운다
    private MatchSummary savedSummary(MatchTarget target, Long userId) {

        MatchDetail detail = matchDetails.detail(target.id(), userId);

        return detail.needsJudgement() ? null : detail.summary();

    }

    private RunItem.Posting posting(MatchTarget target, MatchSummary summary) {

        return new RunItem.Posting(null, target.id(), clip(target.company(), COMPANY_MAX_LENGTH),
                clip(target.role(), TITLE_MAX_LENGTH), daysLeft(target.expiresAt()),
                summary == null ? null : summary.requiredMet(), summary == null ? null : summary.requiredTotal());

    }

    private Integer daysLeft(Instant expiresAt) {

        if (expiresAt == null) {
            return null;
        }

        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        long days = ChronoUnit.DAYS.between(today, expiresAt.atZone(SEOUL).toLocalDate());

        return (int) Math.max(0, days);

    }

    // 필수 조건의 기준은 상세의 '필수 N개 중 M개' 와 같아, 기술·경험 필수 조건만 센다
    private static List<String> texts(List<MatchCondition> required, boolean met) {

        return required.stream()
                .filter(condition -> (condition.status() == MatchConditionStatus.MET) == met)
                .map(condition -> clip(condition.text(), CONDITION_MAX_LENGTH))
                .limit(CONDITIONS_LIMIT)
                .toList();

    }

    private static String clip(String text, int limit) {

        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }

        return text.substring(0, text.offsetByCodePoints(0, limit));

    }

    private static ToolError errorOf(RuntimeException failure) {

        if (failure instanceof MatchPostingNotFoundException || failure instanceof CoverLetterPostingNotFoundException
                || failure instanceof RepoNotFoundException) {
            return ToolError.NOT_FOUND;
        }
        if (failure instanceof NoConfirmedCardsException) {
            return ToolError.NO_CONFIRMED_CARDS;
        }
        if (failure instanceof RateLimitExceededException) {
            return ToolError.RATE_LIMITED;
        }

        return ToolError.FAILED;

    }
}
