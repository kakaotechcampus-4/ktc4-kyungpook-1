package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.mail.domain.MailPosting;
import com.gitory.backend.mail.domain.MailService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.PostingAlertRunRepository;
import com.gitory.backend.matching.infra.SyncLock;
import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticePosting;
import com.gitory.backend.notice.domain.NoticeService;
import com.gitory.backend.profile.domain.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 공고 가져오기가 끝난 뒤 지난 확인 뒤에 조건을 뽑은 공고를 확정 카드와 맞춰 보고, 필수 조건의 절반 이상을 채운 공고를 알림 한 줄로 남긴다
 * 사용자가 누르지 않아도 도는 일이라 호출 한도를 쓰지 않으며, 사용자마다 AI 를 부르는 공고를 5건으로 막아 비용을 묶는다
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "gitory.matching.alert.enabled", havingValue = "true")
public class NewPostingAlertService {

    static final Duration FIRST_LOOKBACK = Duration.ofHours(24);
    static final Duration ACTIVE_WITHIN = Duration.ofDays(14);
    static final int JUDGED_POSTINGS = 5;
    static final int ALERTED_POSTINGS = 3;

    // 채운 필수 조건의 비율이 높은 공고가 먼저고, 같으면 채운 우대 조건 수, 그래도 같으면 기술이 많이 겹친 순서다
    private static final Comparator<Matched> STRONGER_FIRST = Comparator
            .comparing(Matched::notice, NewPostingAlertService::higherRequiredRatioFirst)
            .thenComparing(Comparator.comparingInt(Matched::preferredMet).reversed())
            .thenComparingInt(Matched::order);

    private final PostingAlertRunRepository runs;
    private final JobPostingRepository postings;
    private final MatchingCardReader cards;
    private final CompanyShortlistService shortlist;
    private final MatchJudgementService judgements;
    private final NoticeService notices;
    private final ProfileService profiles;
    private final MailService mails;
    private final SyncLock lock;
    private final Clock clock;
    private final Executor judgeCalls;

    @Autowired
    public NewPostingAlertService(PostingAlertRunRepository runs, JobPostingRepository postings,
                                  MatchingCardReader cards, CompanyShortlistService shortlist,
                                  MatchJudgementService judgements, NoticeService notices, ProfileService profiles,
                                  MailService mails, SyncLock lock, Clock clock) {
        this(runs, postings, cards, shortlist, judgements, notices, profiles, mails, lock, clock,
                Executors.newFixedThreadPool(JUDGED_POSTINGS,
                        Thread.ofPlatform().daemon().name("posting-alert-judge-", 1).factory()));
    }

    NewPostingAlertService(PostingAlertRunRepository runs, JobPostingRepository postings, MatchingCardReader cards,
                           CompanyShortlistService shortlist, MatchJudgementService judgements,
                           NoticeService notices, ProfileService profiles, MailService mails, SyncLock lock,
                           Clock clock, Executor judgeCalls) {
        this.runs = runs;
        this.postings = postings;
        this.cards = cards;
        this.shortlist = shortlist;
        this.judgements = judgements;
        this.notices = notices;
        this.profiles = profiles;
        this.mails = mails;
        this.lock = lock;
        this.clock = clock;
        this.judgeCalls = judgeCalls;
    }

    /** 어떤 예외도 밖으로 내보내지 않으며, 예외로 끝난 실행은 확인한 시각을 옮기지 않아 다음 실행이 같은 구간을 다시 본다 */
    public void run() {

        try {
            if (!lock.runExclusively(this::checkNewPostings)) {
                log.info("새 공고 알림 확인을 건너뜀: 공고 가져오기가 다른 곳에서 실행 중이다");
            }
        } catch (RuntimeException failure) {
            log.error("새 공고 알림 확인이 예상 못 한 오류로 끝났다", failure);
        }

    }

    private void checkNewPostings() {

        Instant started = clock.instant();
        PostingAlertRun state = runs.findById(PostingAlertRun.ONLY_ID).orElse(null);
        Instant after = state == null ? started.minus(FIRST_LOOKBACK) : state.getCheckedThrough();

        Set<Long> targets = new HashSet<>(postings.findIdsExtractedAfter(after, started));
        if (!targets.isEmpty()) {
            Set<Long> mailOptIn = new HashSet<>(profiles.mailOptInUserIds());
            Set<Long> users = new TreeSet<>(cards.activeUserIds(started.minus(ACTIVE_WITHIN)));
            users.addAll(mailOptIn);
            for (Long userId : users) {
                checkUser(userId, targets, mailOptIn.contains(userId));
            }
        }

        if (state == null) {
            runs.save(PostingAlertRun.through(started));
        } else {
            state.advance(started);
            runs.save(state);
        }

    }

    // 한 사용자가 실패해도 다음 사용자를 확인하도록 사용자마다 예외를 삼킨다
    private void checkUser(Long userId, Set<Long> targets, boolean mailOptIn) {

        try {
            leaveNotice(userId, targets, mailOptIn);
        } catch (RuntimeException failure) {
            log.warn("사용자 {} 의 새 공고 알림을 확인하지 못해 건너뛴다", userId, failure);
        }

    }

    private void leaveNotice(Long userId, Set<Long> targets, boolean mailOptIn) {

        List<MatchingCard> all = cards.confirmedOf(userId);
        Set<CardTech> techs = all.stream()
                .flatMap(card -> card.techs().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (techs.isEmpty()) {
            return;
        }

        List<ShortlistedPosting> chosen = shortlist.postingsAmong(userId, techs, targets).stream()
                .limit(JUDGED_POSTINGS)
                .toList();
        List<CompletableFuture<Optional<Matched>>> futures = IntStream.range(0, chosen.size())
                .mapToObj(order -> CompletableFuture.supplyAsync(() -> matched(chosen.get(order), all, order), judgeCalls))
                .toList();
        List<NoticePosting> alerted = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .sorted(STRONGER_FIRST)
                .limit(ALERTED_POSTINGS)
                .map(Matched::notice)
                .toList();

        if (!alerted.isEmpty()) {
            notices.leave(userId, NoticeKind.POSTINGS_MATCHED, NoticePayload.matched(alerted));
            if (mailOptIn) {
                mailTo(userId, alerted);
            }
        }

    }

    private void mailTo(Long userId, List<NoticePosting> alerted) {

        List<MailPosting> mailPostings = alerted.stream()
                .map(posting -> new MailPosting(posting.postingId(), posting.companyName(), posting.title(),
                        posting.requiredMet(), posting.requiredTotal(), posting.expiresAt()))
                .toList();
        profiles.mailAddressOf(userId).ifPresent(to -> mails.sendMatchedPostings(to, mailPostings));

    }

    // 필수 조건의 절반 이상(등급 A·B)을 채운 공고만 남기며, AI 판단을 받지 못한 공고는 이번에는 알리지 않는다
    private Optional<Matched> matched(ShortlistedPosting found, List<MatchingCard> all, int order) {

        List<JobPostingCriterion> conditions = OverallRecommendationService.judged(found.criteria());
        int requiredTotal = OverallRecommendationService.total(conditions, CriterionType.REQUIRED);
        if (requiredTotal == 0) {
            return Optional.empty();
        }

        Optional<Map<Long, List<JudgedSupport>>> judged = judgements.judge(found.posting(), conditions, all);
        if (judged.isEmpty()) {
            return Optional.empty();
        }

        int requiredMet = OverallRecommendationService.met(conditions, CriterionType.REQUIRED, judged.get());
        int preferredMet = OverallRecommendationService.met(conditions, CriterionType.PREFERRED, judged.get());
        MatchGrade grade = MatchGrading.fit(new MatchCount(requiredTotal, requiredMet),
                new MatchCount(OverallRecommendationService.total(conditions, CriterionType.PREFERRED), preferredMet));
        if (grade != MatchGrade.A && grade != MatchGrade.B) {
            return Optional.empty();
        }

        JobPosting posting = found.posting();
        return Optional.of(new Matched(new NoticePosting(posting.getPublicId(), posting.getCompanyName(),
                posting.getTitle(), requiredMet, requiredTotal, posting.getExpiresAt()), preferredMet, order));

    }

    // 비율을 나눗셈 없이 견줘, 4개 중 3개(0.75)가 2개 중 1개(0.5)보다 앞선다
    private static int higherRequiredRatioFirst(NoticePosting left, NoticePosting right) {

        return Integer.compare(right.requiredMet() * left.requiredTotal(), left.requiredMet() * right.requiredTotal());

    }

    private record Matched(NoticePosting notice, int preferredMet, int order) {
    }
}
