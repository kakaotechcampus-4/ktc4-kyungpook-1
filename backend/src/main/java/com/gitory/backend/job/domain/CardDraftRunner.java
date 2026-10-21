package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftPort;
import com.gitory.backend.agent.port.StarDraftRequest;
import com.gitory.backend.agent.port.StarEvidence;
import com.gitory.backend.agent.port.StarEvidenceSource;
import com.gitory.backend.agent.port.StarSourceType;
import com.gitory.backend.card.domain.CardDraftWriter;
import com.gitory.backend.card.domain.GeneratedCard;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.consent.port.DiffEvidence;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.ingest.domain.DraftCommit;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.ingest.domain.StoredPullRequest;
import com.gitory.backend.ingest.port.DiffEvidenceRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateDraftSource;
import com.gitory.backend.recommend.domain.CandidateDraftSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 카드 초안 Job 이 만든 카드를 한 장씩 diff 근거, STAR 초안, 저장 순서로 채우고 카드마다의 결과로 Job 을 끝낸다
 * AI 를 기다리는 동안 DB 커넥션과 잠금을 붙잡지 않도록 진행을 남기거나 저장할 때만 Job 행을 잠그고, 그때 Job 이 RUNNING 이 아니면 멈춘다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardDraftRunner {

    // AI 가 diff 근거를 만들 때 앞의 50개 커밋까지만 읽는다
    private static final int COMMIT_LIMIT = 50;

    private final AnalysisJobRepository jobs;
    private final TransactionTemplate transaction;
    private final GeneratedCardQueryService generatedCards;
    private final CandidateDraftSourceService candidateSources;
    private final UserLoginService users;
    private final RepositoryActivityPort activities;
    private final StoredActivityService storedActivity;
    private final StarDraftPort star;
    private final CardDraftWriter writer;

    public void run(AnalysisJob job) {

        List<GeneratedCard> cards = generatedCards.cardsOf(job.getPublicId());
        String login = users.loginOf(job.getUserId());
        int total = cards.size();
        if (!record(job.getId(), 0, 0, total)) {
            return;
        }

        List<CardDraftResult> results = new ArrayList<>();
        for (GeneratedCard card : cards) {
            Optional<CardDraftResult> result = draft(job, card, login, results.size(), total);
            if (result.isEmpty()) {
                return;
            }
            results.add(result.get());
        }

        transaction.executeWithoutResult(status -> finish(job.getId(), results));

    }

    /**
     * 카드 한 장을 채우고 진행 숫자를 한 장 늘리며, 그사이 Job 이 RUNNING 이 아니게 되면 빈 값을 돌려준다
     * AI 나 저장이 실패한 카드는 실패로 세고 다음 카드로 넘어간다
     */
    private Optional<CardDraftResult> draft(AnalysisJob job, GeneratedCard card, String login, int done, int total) {

        try {
            Optional<CandidateDraftSource> source = sourceOf(card);
            if (source.isEmpty()) {
                return finishCard(job.getId(), done, total, () -> CardDraftResult.SKIPPED);
            }

            List<DraftCommit> commits = source.get().commits();
            DiffEvidenceResult evidence = activities.diffEvidence(new DiffEvidenceRequest(job.getUserRepositoryId(),
                    commits.stream().map(DraftCommit::sha).toList(), source.get().githubPrNumber(), card.title()));
            if (!record(job.getId(), done + 1, done, total)) {
                return Optional.empty();
            }

            if (evidence.evidence().isEmpty()) {
                return finishCard(job.getId(), done, total, () -> noEvidence(card));
            }

            String body = pullRequestBodyOf(job.getUserRepositoryId(), source.get(), login);
            StarDraft draft = star.draft(requestOf(source.get(), card, login, evidence, body));
            return finishCard(job.getId(), done, total, () -> written(card, draft, commits));
        } catch (RuntimeException failure) {
            log.warn("카드 초안 Job {} 의 카드 {} 를 채우지 못했다", job.getId(), card.id(), failure);
            return finishCard(job.getId(), done, total, () -> CardDraftResult.FAILED);
        }

    }

    // 이미 초안을 담았거나, 후보가 없거나, 후보에 남은 커밋이 없는 카드는 AI 를 부르지 않는다
    private Optional<CandidateDraftSource> sourceOf(GeneratedCard card) {

        if (card.candidateId() == null) {
            return Optional.empty();
        }

        if (Boolean.TRUE.equals(transaction.execute(status -> writer.isDrafted(card.id())))) {
            return Optional.empty();
        }

        return candidateSources.load(card.candidateId(), COMMIT_LIMIT)
                .filter(source -> !source.commits().isEmpty());

    }

    private CardDraftResult noEvidence(GeneratedCard card) {

        writer.writeNoEvidence(card.id());
        return CardDraftResult.NO_EVIDENCE;

    }

    private CardDraftResult written(GeneratedCard card, StarDraft draft, List<DraftCommit> commits) {

        Map<String, Long> commitIdsBySha = new HashMap<>();
        for (DraftCommit commit : commits) {
            commitIdsBySha.put(commit.sha().toLowerCase(Locale.ROOT), commit.id());
        }

        return writer.writeStar(card.id(), draft, commitIdsBySha) ? CardDraftResult.TIMED_OUT : CardDraftResult.DRAFTED;

    }

    /** Job 행을 잠가 아직 RUNNING 일 때만 카드에 쓰고 진행 숫자를 한 장 늘린다 */
    private Optional<CardDraftResult> finishCard(Long jobId, int done, int total, Supplier<CardDraftResult> write) {

        return transaction.execute(status -> {
            AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
            if (job.getState() != JobState.RUNNING) {
                return Optional.empty();
            }
            CardDraftResult result = write.get();
            job.recordDraftProgress(done + 1, done + 1, total);
            return Optional.of(result);
        });

    }

    private boolean record(Long jobId, int read, int finished, int total) {

        return Boolean.TRUE.equals(transaction.execute(status -> {
            AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
            if (job.getState() != JobState.RUNNING) {
                return false;
            }
            job.recordDraftProgress(read, finished, total);
            return true;
        }));

    }

    private void finish(Long jobId, List<CardDraftResult> results) {

        AnalysisJob job = jobs.findWithLockById(jobId).orElseThrow();
        if (job.getState() == JobState.RUNNING) {
            job.finishDraft(results);
        }

    }

    // 카드 주인이 쓴 PR 의 본문만 근거로 보내고, 남이 쓴 PR·저장되지 않은 PR·빈 본문은 보내지 않는다
    private String pullRequestBodyOf(Long userRepositoryId, CandidateDraftSource source, String login) {

        if (source.githubPrNumber() == null) {
            return null;
        }

        return storedActivity.findPullRequest(userRepositoryId, source.githubPrNumber())
                .filter(pullRequest -> login.equalsIgnoreCase(pullRequest.authorLogin()))
                .map(StoredPullRequest::bodyExcerpt)
                .filter(body -> !body.isBlank())
                .orElse(null);

    }

    // 사용자가 쓴 확인 답변은 보내지 않아, AI 는 커밋 근거와 카드 주인이 쓴 PR 본문으로 칸을 채운다
    private static StarDraftRequest requestOf(CandidateDraftSource source, GeneratedCard card, String login,
                                              DiffEvidenceResult evidence, String pullRequestBody) {

        String sourceRef = source.sourceRef() == null || source.sourceRef().isBlank() ? null : source.sourceRef();
        return new StarDraftRequest(source.publicId().toString(), login, card.title(),
                StarSourceType.valueOf(source.type().name()), sourceRef,
                evidence.evidence().stream().map(CardDraftRunner::starEvidenceOf).toList(), pullRequestBody);

    }

    // 바뀐 파일 경로는 AI 가 기술 태그를 고를 재료라 저장하지 않고 넘기기만 한다
    // 빈 목록이면 STAR 요청에서 칸을 빼서, 이 칸을 모르는 AI 가 모르는 칸으로 요청을 거절하지 않게 한다
    private static StarEvidence starEvidenceOf(DiffEvidence item) {

        return new StarEvidence(item.sha(), item.message(), item.authorLogin(), item.churn(), item.summary(),
                StarEvidenceSource.valueOf(item.summarySource().name()), item.technicalPoints(), item.url(),
                item.changedPaths());

    }
}
