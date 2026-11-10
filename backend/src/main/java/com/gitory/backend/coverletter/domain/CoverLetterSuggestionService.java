package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterSuggestionPort;
import com.gitory.backend.agent.port.CoverLetterSuggestionRequest;
import com.gitory.backend.agent.port.CoverLetterSuggestions;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.ingest.domain.ConnectedRepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.UnusedCandidate;
import com.gitory.backend.recommend.domain.UnusedCandidateReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 중심 카드가 없는 문항에 쓸 만한 확정 카드와 레포 기록(경험 후보)을 AI 에게 골라 받아 문항에 저장한다
 * 이 자소서가 아직 쓰지 않은 카드만 보내며, 호출 제한을 넘었거나 AI 가 응답하지 못하면 저장하지 않아 다음에 문항을 열 때 다시 묻는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverLetterSuggestionService {

    // AI 제안 계약이 받는 상한이다
    private static final int MAX_CARDS = 30;
    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_COMMIT_MESSAGES = 5;
    private static final int CARD_POOL = 100;
    private static final int MAX_REPOS = 300;

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final CardMaterialReader cardMaterials;
    private final RepositoryReferenceService repositories;
    private final UnusedCandidateReader candidates;
    private final CoverLetterSuggestionPort port;
    private final UserRateLimiter rateLimiter;
    private final PlatformTransactionManager transactionManager;

    /** 이미 물었거나 중심 카드가 있거나 보낼 카드·후보가 없으면 AI 를 부르지 않고, 어떤 실패도 문항 열기를 막지 않는다 */
    public void suggestIfNeeded(UUID letterId, UUID questionId, Long userId, String login) {

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        Prepared prepared = read.execute(status -> prepare(letterId, questionId, userId, login));
        if (prepared == null) {
            return;
        }

        try {
            rateLimiter.acquire(RateLimitedAction.COVER_LETTER_SUGGEST, userId, 1);
        } catch (RateLimitExceededException exceeded) {
            log.warn("자소서 제안 호출 한도를 넘어 건너뛴다 userId={}", userId);
            return;
        }
        AtomicReference<CoverLetterSuggestions> result = new AtomicReference<>();
        try {
            AiCallContext.runForCoverLetter(prepared.request().coverLetterId(),
                    () -> result.set(port.suggest(prepared.request())));
        } catch (AgentUnavailableException failure) {
            log.warn("자소서 제안 받기 실패 coverLetterId={} questionId={}: {}", prepared.request().coverLetterId(),
                    questionId, failure.getMessage());
            return;
        }

        List<Suggestion> suggestions = result.get().suggestions().stream().map(prepared::suggestionOf).toList();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> save(letterId, questionId, userId, suggestions));

    }

    private Prepared prepare(UUID letterId, UUID questionId, Long userId, String login) {

        CoverLetter letter = letters.findByPublicIdAndUserId(letterId, userId).orElse(null);
        if (letter == null || letter.getStage() != CoverLetterStage.DRAFTED) {
            return null;
        }
        List<CoverLetterQuestion> loaded = questions.findByCoverLetterIdOrderBySeq(letter.getId());
        CoverLetterQuestion target = loaded.stream()
                .filter(question -> question.getPublicId().equals(questionId))
                .findFirst()
                .orElse(null);
        if (target == null || target.getPrimaryCardId() != null || target.getSuggestions() != null) {
            return null;
        }

        Set<Long> used = loaded.stream()
                .flatMap(question -> Stream.of(question.getPrimaryCardId(), question.getSupportingCardId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<CardMaterial> cards = cardMaterials.read(userId, CARD_POOL).stream()
                .filter(card -> !used.contains(card.id()))
                .limit(MAX_CARDS)
                .toList();
        Map<Long, ConnectedRepositoryReference> repos = repositories.connectedOf(userId, login, MAX_REPOS).stream()
                .collect(Collectors.toMap(ConnectedRepositoryReference::id, Function.identity()));
        List<UnusedCandidate> unused = candidates.of(repos.keySet(), MAX_CANDIDATES, MAX_COMMIT_MESSAGES);
        if (cards.isEmpty() && unused.isEmpty()) {
            return null;
        }

        Map<String, Known> known = new HashMap<>();
        cards.forEach(card -> known.put(key(SuggestionSource.CARD, String.valueOf(card.id())),
                new Known(card.publicId().toString(), card.title(), null)));
        unused.forEach(candidate -> known.put(key(SuggestionSource.CANDIDATE, candidate.publicId().toString()),
                new Known(candidate.publicId().toString(), candidate.title(),
                        repos.get(candidate.userRepositoryId()).publicId())));

        CoverLetterSuggestionRequest request = new CoverLetterSuggestionRequest(letter.getId(), letter.getJobRole(),
                letter.memoForWriting(),
                new CoverLetterSuggestionRequest.Question(target.getSeq(), target.getText(), target.getCharLimit(),
                        target.getTheme() == null ? null : target.getTheme().name()),
                cards.stream()
                        .map(card -> new CoverLetterSuggestionRequest.Card(card.id(), card.title(), card.kind().name(),
                                card.sourceName(), card.period(), CoverLetterCardSummary.of(card)))
                        .toList(),
                unused.stream()
                        .map(candidate -> new CoverLetterSuggestionRequest.Candidate(candidate.publicId().toString(),
                                candidate.title(), candidate.reason() == null ? "" : candidate.reason(),
                                fullName(repos.get(candidate.userRepositoryId())), candidate.commitMessages(),
                                candidate.reviewCommentCount()))
                        .toList(),
                CoverLetterPosting.inputOf(letter));

        return new Prepared(request, known);

    }

    // 기다리는 사이 사용자가 카드를 골랐거나 다른 요청이 먼저 저장했으면 그 결과를 덮지 않는다
    private void save(UUID letterId, UUID questionId, Long userId, List<Suggestion> suggestions) {

        letters.findWithLockByPublicIdAndUserId(letterId, userId)
                .flatMap(letter -> questions.findByPublicIdAndCoverLetterId(questionId, letter.getId()))
                .filter(question -> question.getPrimaryCardId() == null && question.getSuggestions() == null)
                .ifPresent(question -> question.suggest(suggestions));

    }

    private static String key(SuggestionSource source, String aiId) {

        return source + ":" + aiId;

    }

    private static String fullName(ConnectedRepositoryReference repo) {

        return repo.ownerLogin() + "/" + repo.name();

    }

    private record Known(String id, String title, UUID repoId) {
    }

    private record Prepared(CoverLetterSuggestionRequest request, Map<String, Known> known) {

        Suggestion suggestionOf(CoverLetterSuggestions.Item item) {

            SuggestionSource source = SuggestionSource.valueOf(item.source());
            Known found = known.get(key(source, item.id()));

            return new Suggestion(source, found.id(), found.title(), item.angle(), found.repoId());

        }
    }
}
