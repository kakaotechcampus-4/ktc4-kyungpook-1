package com.gitory.backend.assistant.domain;

import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.card.domain.interview.InterviewAskService;
import com.gitory.backend.card.domain.interview.InterviewCapReachedException;
import com.gitory.backend.card.domain.interview.InterviewTurnView;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.coverletter.domain.CoverLetterNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterPolishService;
import com.gitory.backend.coverletter.domain.CoverLetterPostingNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterQueryService;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.coverletter.domain.NoConfirmedCardsException;
import com.gitory.backend.matching.domain.GapFillNeedsJudgementException;
import com.gitory.backend.matching.domain.GapFillService;
import com.gitory.backend.matching.domain.GapFillStatus;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillView;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * AI 가 고른 도구 일곱을 이미 있는 공고 찾기·맞춰 보기·모자란 조건 채우기·카드 빈 칸 질문·자소서·첨삭·레포 정리 기능으로 실행하고 결과를 걸음에 남길 값으로 바꾼다
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
    // 채우기는 한 번에 90초 안팎이라 그보다 길게 기다리면 멈춘 것으로 본다
    private static final long FILL_WAIT_LIMIT_MS = 90_000;
    private static final long FILL_DEFAULT_POLL_MS = 1_500;
    // 첨삭은 문항마다 AI 를 한 번 부르므로 한 걸음에 세 문항까지만 한다
    private static final int POLISH_LIMIT = 3;

    private final MatchListService matchList;
    private final MatchDetailService matchDetails;
    private final CoverLetterService coverLetters;
    private final MeCardStatsService cardStats;
    private final RepoAnalysisPort repoAnalysis;
    private final GapFillService gapFills;
    private final UserLoginService users;
    private final InterviewAskService interviews;
    private final CoverLetterQueryService letterReader;
    private final CoverLetterPolishService polishes;
    private final PollSleeper sleeper;
    private final Clock clock;

    /** seq 는 이 걸음의 번호로, 같은 걸음을 다시 실행해도 자소서가 둘 생기지 않게 자소서 요청 키에 쓴다 */
    public ToolResult execute(Long userId, int seq, ToolCall call) {

        try {
            return switch (call.tool()) {
                case FIND_POSTINGS -> findPostings(userId, call.find());
                case MATCH_POSTING -> matchPosting(userId, call.posting());
                case START_COVER_LETTER -> startCoverLetter(userId, seq, call);
                case START_REPO_ANALYSIS -> startRepoAnalysis(userId, call.repo());
                case FILL_POSTING_GAPS -> fillPostingGaps(userId, call.posting());
                case ASK_CARD_GAP -> askCardGap(userId, call.card());
                case POLISH_COVER_LETTER -> polishCoverLetter(userId, call.letter());
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

        return ToolResult.matched(posting.withRequired(summary.requiredMet(), summary.requiredTotal()),
                matchOf(detail));

    }

    /**
     * 채우기가 끝날 때까지 상태를 읽으며 기다린 뒤 상세를 다시 읽어, 맞은 필수 조건이 늘어난 수를 남긴다
     * 맞춰 보지 않은 공고는 GapFillService 가 거절하고, 90초가 지나도록 끝나지 않으면 실패로 남긴다
     */
    private ToolResult fillPostingGaps(Long userId, RunItem.Posting posting) {

        gapFills.start(posting.postingId(), userId, users.loginOf(userId));
        GapFillView done = waitUntilDone(posting.postingId(), userId);
        if (done == null) {
            return ToolResult.failed(ToolError.FAILED);
        }

        MatchDetail after = matchDetails.detail(posting.postingId(), userId);
        if (after.summary() == null) {
            return ToolResult.failed(ToolError.FAILED);
        }

        // 채우기는 카드 제안까지만 하고 맞는 조건 수는 바꾸지 않으므로, 근거를 찾은 조건 수를 돌려준다
        int filled = (int) done.conditions().stream().filter(condition -> condition.result() == GapFillResult.PROPOSED).count();

        return ToolResult.gapsFilled(
                posting.withRequired(after.summary().requiredMet(), after.summary().requiredTotal()), matchOf(after),
                filled);

    }

    // 실제 시계가 아니라 쉰 시간을 더해 한도를 재서, 시험이 시계를 흉내 내지 않아도 된다
    private GapFillView waitUntilDone(UUID postingId, Long userId) {

        long waited = 0;
        while (true) {
            GapFillView view = gapFills.of(postingId, userId).orElse(null);
            if (view == null || view.state() == GapFillStatus.FAILED) {
                return null;
            }
            if (view.state() == GapFillStatus.DONE) {
                return view;
            }
            if (waited >= FILL_WAIT_LIMIT_MS) {
                return null;
            }

            long pause = view.pollAfterMs() == null ? FILL_DEFAULT_POLL_MS : view.pollAfterMs();
            try {
                sleeper.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            waited += pause;
        }

    }

    /** 카드의 어느 빈 칸을 물을지는 InterviewAskService 가 고르며, 더 물을 것이 없으면 질문 없이 성공으로 남긴다 */
    private ToolResult askCardGap(Long userId, CardLabel card) {

        InterviewTurnView turn = interviews.ask(card.cardId(), userId, null);

        return ToolResult.asked(card.cardId(), card.title(), turn == null ? null : turn.field().name(),
                turn == null ? null : turn.question());

    }

    /**
     * 아직 첨삭하지 않은 문항을 문항 번호 순서로 세 개까지 첨삭하고, 중간에 막히면 그때까지 한 것을 남긴다
     * 첫 문항부터 막히면 그 이유를 걸음 실패로 남긴다
     */
    private ToolResult polishCoverLetter(Long userId, LetterLabel label) {

        CoverLetterView letter = letterReader.load(label.letterId(), userId);
        if (letter.stage() != CoverLetterStage.DRAFTED) {
            return ToolResult.failed(ToolError.NOT_READY);
        }

        // 빈 글은 AI 가 첨삭하지 않아 세지 않는다
        List<CoverLetterView.Question> unpolished = letter.questions().stream()
                .filter(question -> question.polish() == null && question.draft() != null
                        && !question.draft().text().isBlank())
                .sorted(Comparator.comparingInt(CoverLetterView.Question::seq))
                .toList();
        List<RunItem.Polished> done = new ArrayList<>();
        for (CoverLetterView.Question question : unpolished.stream().limit(POLISH_LIMIT).toList()) {
            try {
                CoverLetterView polished = polishes.polish(label.letterId(), question.id(), userId);
                done.add(new RunItem.Polished(question.seq(), pointsOf(polished, question.id())));
            } catch (RuntimeException failure) {
                if (done.isEmpty()) {
                    throw failure;
                }
                log.warn("자소서 첨삭을 {} 문항까지 하고 멈춘다", done.size(), failure);
                break;
            }
        }

        return ToolResult.polished(label.letterId(), done, unpolished.size() - done.size());

    }

    private static int pointsOf(CoverLetterView letter, UUID questionId) {

        return letter.questions().stream()
                .filter(question -> question.id().equals(questionId) && question.polish() != null)
                .mapToInt(question -> question.polish().points().size())
                .findFirst()
                .orElse(0);

    }

    private static RunItem.Match matchOf(MatchDetail detail) {

        MatchSummary summary = detail.summary();
        List<MatchCondition> required = detail.conditions().stream()
                .filter(condition -> condition.type() == CriterionType.REQUIRED && condition.generalType() == null)
                .toList();

        return new RunItem.Match(summary.requiredMet(), summary.requiredTotal(), texts(required, true),
                texts(required, false));

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

    static String clip(String text, int limit) {

        if (text == null || text.codePointCount(0, text.length()) <= limit) {
            return text;
        }

        return text.substring(0, text.offsetByCodePoints(0, limit));

    }

    private static ToolError errorOf(RuntimeException failure) {

        if (failure instanceof MatchPostingNotFoundException || failure instanceof CoverLetterPostingNotFoundException
                || failure instanceof RepoNotFoundException || failure instanceof CardNotFoundException
                || failure instanceof CoverLetterNotFoundException) {
            return ToolError.NOT_FOUND;
        }
        if (failure instanceof GapFillNeedsJudgementException) {
            return ToolError.NOT_MATCHED;
        }
        if (failure instanceof GithubNotConnectedException) {
            return ToolError.GITHUB_DISCONNECTED;
        }
        if (failure instanceof InterviewCapReachedException) {
            return ToolError.CAP_REACHED;
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
