package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardLabel;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ConnectedRepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchGapFillRepository;
import com.gitory.backend.recommend.domain.UnusedCandidate;
import com.gitory.backend.recommend.domain.UnusedCandidateReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 맞춰 본 공고에서 못 채운 조건마다 AI 가 내 자료에서 채울 방법을 찾게 하고, 찾는 동안은 메모리에, 끝나면 DB 에 둔다
 * 사용자에게 묻지 않으며, 같은 자료로 이미 찾은 공고는 AI 도 호출 한도도 쓰지 않고 저장한 결과를 보인다
 */
@Slf4j
@Service
public class GapFillService {

    // AI 채우기 API 가 한 번에 받는 상한이다
    static final int MAX_CONDITIONS = 5;
    static final int MAX_CARDS = 40;
    static final int MAX_DRAFT_CARDS = 20;
    static final int MAX_CANDIDATES = 100;
    static final int MAX_REPOS = 300;
    // 후보가 100개까지라 커밋 메시지는 AI 가 받는 30개보다 적게 보내 요청 크기를 줄인다
    static final int MAX_COMMIT_MESSAGES = 15;

    static final int POLL_AFTER_MS = 1500;
    // 한 번이 90초 안팎이라, 여러 사용자가 같이 눌러도 앞사람이 끝나기를 오래 기다리지 않게 둘씩 돌린다
    private static final int PARALLEL_RUNS = 2;

    private final MatchDetailService details;
    private final JobPostingRepository postings;
    private final MatchingCardReader cards;
    private final UnusedCandidateReader candidates;
    private final RepositoryReferenceService repositories;
    private final GithubCollectionAccessPort githubAccess;
    private final GapFillPort port;
    private final MatchGapFillRepository fills;
    private final GapFillLiveLog liveLog;
    private final UserRateLimiter rateLimiter;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor runner;

    @Autowired
    public GapFillService(MatchDetailService details, JobPostingRepository postings, MatchingCardReader cards,
                          UnusedCandidateReader candidates, RepositoryReferenceService repositories,
                          GithubCollectionAccessPort githubAccess, GapFillPort port, MatchGapFillRepository fills,
                          GapFillLiveLog liveLog, UserRateLimiter rateLimiter, TransactionTemplate transaction,
                          ObjectMapper mapper, Clock clock) {
        this(details, postings, cards, candidates, repositories, githubAccess, port, fills, liveLog, rateLimiter,
                transaction, mapper, clock, Executors.newFixedThreadPool(PARALLEL_RUNS,
                        Thread.ofPlatform().daemon().name("gap-fill-", 1).factory()));
    }

    GapFillService(MatchDetailService details, JobPostingRepository postings, MatchingCardReader cards,
                   UnusedCandidateReader candidates, RepositoryReferenceService repositories,
                   GithubCollectionAccessPort githubAccess, GapFillPort port, MatchGapFillRepository fills,
                   GapFillLiveLog liveLog, UserRateLimiter rateLimiter, TransactionTemplate transaction,
                   ObjectMapper mapper, Clock clock, Executor runner) {
        this.details = details;
        this.postings = postings;
        this.cards = cards;
        this.candidates = candidates;
        this.repositories = repositories;
        this.githubAccess = githubAccess;
        this.port = port;
        this.fills = fills;
        this.liveLog = liveLog;
        this.rateLimiter = rateLimiter;
        this.transaction = transaction;
        this.mapper = mapper;
        this.clock = clock;
        this.runner = runner;
    }

    /**
     * 저장된 판단으로 상세를 읽어(AI 없이) 못 채운 조건을 배경에서 찾기 시작하고 그 상세를 돌려준다
     * 아직 맞춰 보지 않은 조건이 있으면 GapFillNeedsJudgementException 이고, 찾는 중이거나 같은 자료로 이미 찾았으면 다시 시작하지 않는다
     */
    public MatchDetail start(UUID postingId, Long userId, String login) {

        MatchDetail detail = details.detail(postingId, userId);
        if (detail.needsJudgement()) {
            throw new GapFillNeedsJudgementException();
        }
        Long jobPostingId = postings.findIdByPublicId(postingId).orElseThrow(MatchPostingNotFoundException::new);
        // DB 가 마이크로초까지 저장하므로, 저장할 때 시작 시각을 견주려면 같은 단위로 자른다
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<MatchCondition> gaps = gapsOf(detail.conditions());
        if (gaps.isEmpty()) {
            fills.finishEmpty(userId, jobPostingId, now, now.minus(MatchGapFill.STUCK_AFTER));
            return detail;
        }
        Optional<MatchGapFill> saved = fills.findByUserIdAndJobPostingId(userId, jobPostingId);
        if (saved.filter(fill -> fill.getStatus() == GapFillStatus.RUNNING && !fill.isStuck(now)).isPresent()) {
            return detail;
        }
        GapFillMaterials materials = materials(detail, gaps, List.of(), userId, login);
        if (saved.filter(fill -> fill.getStatus() == GapFillStatus.DONE
                && materials.fingerprint().equals(fill.getFingerprint())).isPresent()) {
            return detail;
        }

        // 내 커밋 검색과 제안 실행(카드 초안·레포 분석)에 GitHub 이 필요해, 새로 찾을 때만 연결을 먼저 본다
        githubAccess.requireUsableConnection(userId);
        rateLimiter.acquire(RateLimitedAction.MATCH_GAP_FILL, userId, 1);
        List<GapFillCondition> waiting = gaps.stream().map(gap -> GapFillCondition.waiting(gap.id())).toList();
        if (fills.start(userId, jobPostingId, mapper.writeValueAsString(waiting), now,
                now.minus(MatchGapFill.STUCK_AFTER)) == 0) {
            return detail;
        }
        GapFillLiveLog.Live live = liveLog.start(userId, jobPostingId, waiting);
        runner.execute(() -> run(userId, jobPostingId, now, materials, live, waiting, materials.fingerprint()));
        return detail;

    }

    /**
     * 끝난 결과에서 '찾지 않음' 으로 멈춘 조건만 AI 가 첫 판단과 상관없이 찾게 하고, 끝나면 그 조건만 바꿔 저장한다
     * 끝난 결과가 없으면 GapFillNotFinishedException, 멈춘 조건이 아니거나 지금도 못 채운 조건이 아니면 GapFillNotSkippedException 이다
     */
    public MatchDetail force(UUID postingId, Long userId, String login, List<UUID> conditionIds) {

        MatchDetail detail = details.detail(postingId, userId);
        if (detail.needsJudgement()) {
            throw new GapFillNeedsJudgementException();
        }
        Long jobPostingId = postings.findIdByPublicId(postingId).orElseThrow(MatchPostingNotFoundException::new);
        MatchGapFill saved = fills.findByUserIdAndJobPostingId(userId, jobPostingId)
                .filter(fill -> fill.getStatus() == GapFillStatus.DONE)
                .orElseThrow(GapFillNotFinishedException::new);
        Set<UUID> chosen = new HashSet<>(conditionIds);
        Set<UUID> skipped = saved.getConditions().stream()
                .filter(condition -> condition.result() == GapFillResult.SKIPPED)
                .map(GapFillCondition::conditionId)
                .collect(Collectors.toSet());
        List<MatchCondition> forced = detail.conditions().stream()
                .filter(condition -> chosen.contains(condition.id()) && skipped.contains(condition.id()))
                .filter(condition -> condition.status() == MatchConditionStatus.NOT_MET)
                .toList();
        if (forced.size() != chosen.size()) {
            throw new GapFillNotSkippedException();
        }
        GapFillMaterials materials = materials(detail, forced, forced.stream().map(MatchCondition::id).toList(),
                userId, login);

        githubAccess.requireUsableConnection(userId);
        rateLimiter.acquire(RateLimitedAction.MATCH_GAP_FILL, userId, 1);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<GapFillCondition> conditions = saved.getConditions().stream()
                .map(condition -> chosen.contains(condition.conditionId())
                        ? GapFillCondition.waiting(condition.conditionId())
                        : condition)
                .toList();
        if (fills.restart(userId, jobPostingId, mapper.writeValueAsString(conditions), now,
                saved.getRequestedAt()) == 0) {
            return detail;
        }
        GapFillLiveLog.Live live = liveLog.start(userId, jobPostingId, conditions);
        runner.execute(() -> run(userId, jobPostingId, now, materials, live, conditions, saved.getFingerprint()));
        return detail;

    }

    /** 채우기를 시작한 적이 없으면 빈 값이며, 찾는 중이면 메모리의 진행을, 시작한 지 오래된 찾기는 실패로 보인다 */
    public Optional<GapFillView> of(UUID postingId, Long userId) {

        Optional<Long> jobPostingId = postings.findIdByPublicId(postingId);
        if (jobPostingId.isEmpty()) {
            return Optional.empty();
        }
        // 저장한 뒤 메모리 진행을 지우므로, 진행을 먼저 읽어야 RUNNING 줄을 읽고도 진행이 사라져 빈 조건이 보이는 일이 없다
        Optional<List<GapFillCondition>> live = liveLog.of(userId, jobPostingId.get());
        Instant now = Instant.now(clock);
        return fills.findByUserIdAndJobPostingId(userId, jobPostingId.get()).map(saved -> {
            if (saved.isStuck(now)) {
                return new GapFillView(GapFillStatus.FAILED, null, saved.getConditions());
            }
            if (saved.getStatus() == GapFillStatus.RUNNING) {
                return new GapFillView(GapFillStatus.RUNNING, POLL_AFTER_MS, live.orElse(saved.getConditions()));
            }
            return new GapFillView(saved.getStatus(), null, saved.getConditions());
        });

    }

    // 필수 조건을 먼저 채우고 필수를 다 채운 공고만 우대 조건을 찾으며, 학력·어학 같은 일반 조건은 사용자가 직접 확인할 일이라 넣지 않는다
    static List<MatchCondition> gapsOf(List<MatchCondition> conditions) {

        List<MatchCondition> required = notMet(conditions, CriterionType.REQUIRED);
        return (required.isEmpty() ? notMet(conditions, CriterionType.PREFERRED) : required).stream()
                .limit(MAX_CONDITIONS)
                .toList();

    }

    private static List<MatchCondition> notMet(List<MatchCondition> conditions, CriterionType type) {

        return conditions.stream()
                .filter(condition -> condition.type() == type)
                .filter(condition -> condition.generalType() == null)
                .filter(condition -> condition.status() == MatchConditionStatus.NOT_MET)
                .toList();

    }

    // AI 가 고른 id 를 그대로 화면에 보내도록 모든 id 는 공개 id 로 보낸다
    private GapFillMaterials materials(MatchDetail detail, List<MatchCondition> gaps, List<UUID> force, Long userId,
                                      String login) {

        // 카드 id 순으로 오므로, 확정 카드가 상한보다 많으면 최근에 만든 카드를 보낸다
        List<MatchingCard> allConfirmed = cards.confirmedOf(userId);
        List<MatchingCard> confirmed = allConfirmed.subList(Math.max(0, allConfirmed.size() - MAX_CARDS),
                allConfirmed.size());
        List<MatchingCard> drafts = cards.draftsOf(userId, MAX_DRAFT_CARDS);
        Map<Long, MatchingCardLabel> labels = cards.labelsOf(Stream.concat(confirmed.stream(), drafts.stream())
                .map(MatchingCard::id)
                .toList());
        Map<UUID, MatchingCard> confirmedById = byPublicId(confirmed, labels);
        Map<UUID, MatchingCard> draftsById = byPublicId(drafts, labels);
        List<ConnectedRepositoryReference> repos = repositories.connectedOf(userId, login, MAX_REPOS);
        Map<Long, ConnectedRepositoryReference> reposById = repos.stream()
                .collect(Collectors.toMap(ConnectedRepositoryReference::id, Function.identity()));
        List<UnusedCandidate> unused = candidates.of(reposById.keySet(), MAX_CANDIDATES, MAX_COMMIT_MESSAGES);

        GapFillRequest request = new GapFillRequest(detail.target().company(), detail.target().role(),
                gaps.stream().map(gap -> new GapFillRequest.Condition(gap.id(), gap.type(), gap.text())).toList(),
                cardsOf(confirmedById), unused.stream()
                        .map(candidate -> new GapFillRequest.Candidate(candidate.publicId(),
                                fullName(reposById.get(candidate.userRepositoryId())), candidate.title(),
                                candidate.reason(), candidate.commitMessages()))
                        .toList(),
                cardsOf(draftsById), repos.stream()
                        .map(repo -> new GapFillRequest.Repo(repo.publicId(), fullName(repo),
                                repo.lastAnalyzedAt() != null))
                        .toList(),
                login, force);
        String fingerprint = GapFillFingerprint.of(gaps.stream().map(MatchCondition::id).toList(), confirmedById,
                draftsById, unused.stream().map(UnusedCandidate::publicId).toList(), request.repos().stream()
                        .collect(Collectors.toMap(GapFillRequest.Repo::repoId, GapFillRequest.Repo::analyzed)));
        Map<UUID, String> draftTitles = drafts.stream()
                .filter(card -> labels.containsKey(card.id()))
                .collect(Collectors.toMap(card -> labels.get(card.id()).publicId(),
                        card -> labels.get(card.id()).title()));
        Map<UUID, UUID> candidateRepos = unused.stream()
                .collect(Collectors.toMap(UnusedCandidate::publicId,
                        candidate -> reposById.get(candidate.userRepositoryId()).publicId()));
        return new GapFillMaterials(request, draftTitles, candidateRepos, fingerprint);

    }

    private static Map<UUID, MatchingCard> byPublicId(List<MatchingCard> found, Map<Long, MatchingCardLabel> labels) {

        Map<UUID, MatchingCard> byId = new LinkedHashMap<>();
        found.stream()
                .filter(card -> labels.containsKey(card.id()))
                .forEach(card -> byId.put(labels.get(card.id()).publicId(), card));
        return byId;

    }

    // 맞춰 보기 판단과 같은 카드 글을 보내 두 AI 가 같은 카드를 보게 한다
    private static List<GapFillRequest.Card> cardsOf(Map<UUID, MatchingCard> byId) {

        return byId.entrySet().stream()
                .map(entry -> {
                    MatchingCard card = entry.getValue();
                    return new GapFillRequest.Card(entry.getKey(), card.title(), card.situation(), card.task(),
                            card.action(), card.result(), card.techs().stream().map(CardTech::name).toList());
                })
                .toList();

    }

    private static String fullName(ConnectedRepositoryReference repo) {

        return repo.ownerLogin() + "/" + repo.name();

    }

    // base 는 저장할 조건 전체의 순서이고, AI 가 찾은 조건만 바꿔 넣어 다시 찾기에서 고르지 않은 조건은 앞 결과 그대로 둔다
    private void run(Long userId, Long jobPostingId, Instant startedAt, GapFillMaterials materials,
                     GapFillLiveLog.Live live, List<GapFillCondition> base, String fingerprint) {

        try {
            Map<UUID, GapFillCondition> filled = port.fill(userId, materials.request(),
                            condition -> live.update(materials.enrich(condition))).stream()
                    .map(materials::enrich)
                    .collect(Collectors.toMap(GapFillCondition::conditionId, Function.identity()));
            List<GapFillCondition> merged = base.stream()
                    .map(condition -> filled.getOrDefault(condition.conditionId(), condition))
                    .toList();
            save(userId, jobPostingId, startedAt, fill -> fill.finish(merged, fingerprint, Instant.now(clock)));
        } catch (RuntimeException failure) {
            log.warn("사용자 {} 의 공고 {} 에서 모자란 조건 채우기를 마치지 못해 실패로 둔다", userId, jobPostingId, failure);
            save(userId, jobPostingId, startedAt, fill -> fill.fail(Instant.now(clock)));
        } finally {
            liveLog.finish(userId, jobPostingId, live);
        }

    }

    // 기다리는 사이 멈춘 찾기로 보여 다시 시작했으면 시작 시각이 달라져, 늦게 끝난 이번 결과는 버린다
    private void save(Long userId, Long jobPostingId, Instant startedAt, Consumer<MatchGapFill> change) {

        transaction.executeWithoutResult(status -> fills.findWithLockByUserIdAndJobPostingId(userId, jobPostingId)
                .filter(fill -> fill.getStatus() == GapFillStatus.RUNNING && startedAt.equals(fill.getRequestedAt()))
                .ifPresent(change));

    }
}
