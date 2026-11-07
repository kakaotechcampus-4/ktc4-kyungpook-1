package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantStepRequest;
import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.ingest.domain.RepositoryListService;
import com.gitory.backend.ingest.domain.RepositorySummary;
import com.gitory.backend.profile.domain.InterestCategory;
import com.gitory.backend.profile.domain.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI 에 보낼 요청을 만든다 — 사용자의 말 전부, 끝난 걸음, 지금 읽은 확정 카드 수·레포 상태·관심 분야, 남은 걸음 수
 * 하는 중인 걸음은 결과가 없어 보내지 않고, 레포 이름표는 실행을 만들 때 정한 것을 그대로 쓴다
 */
@Component
@RequiredArgsConstructor
class AssistantStepRequestFactory {

    private static final int MAX_INTERESTS = 5;
    // ETC 는 낱말로 바꿀 만한 분야가 아니라 뺀다
    private static final Map<InterestCategory, String> INTEREST_WORDS = Map.of(
            InterestCategory.BACKEND, "백엔드",
            InterestCategory.FRONTEND, "프론트엔드",
            InterestCategory.MOBILE, "모바일",
            InterestCategory.DEVOPS, "인프라",
            InterestCategory.DATA, "데이터",
            InterestCategory.AI_ML, "AI",
            InterestCategory.SECURITY, "보안",
            InterestCategory.QA, "QA");

    private final MeCardStatsService cardStats;
    private final UserLoginService users;
    private final RepositoryListService repositories;
    private final RepoAnalysisPort repoAnalysis;
    private final ProfileService profiles;

    AssistantStepRequest of(AssistantRun run) {

        List<AssistantStepRequest.Message> messages = run.getItems().stream()
                .filter(item -> item.kind() == RunItem.Kind.USER)
                .map(item -> new AssistantStepRequest.Message(item.seq(), item.text()))
                .toList();
        List<AssistantStepRequest.DoneStep> steps = run.getItems().stream()
                .filter(item -> item.kind() == RunItem.Kind.STEP && item.ok() != null)
                .map(AssistantStepRequestFactory::doneStep)
                .toList();

        return new AssistantStepRequest(messages, steps, context(run),
                Math.max(0, run.getStepLimit() - run.getStepsUsed()));

    }

    private AssistantStepRequest.Context context(AssistantRun run) {

        Long userId = run.getUserId();

        return new AssistantStepRequest.Context(cardStats.confirmedCards(userId), repos(run),
                profiles.of(userId).interests().stream()
                        .map(INTEREST_WORDS::get)
                        .filter(word -> word != null)
                        .limit(MAX_INTERESTS)
                        .toList());

    }

    private List<AssistantStepRequest.Repo> repos(AssistantRun run) {

        if (run.getRepos().isEmpty()) {
            return List.of();
        }

        Long userId = run.getUserId();
        Set<UUID> analyzing = repoAnalysis.analyzingRepoIds(userId);
        Set<UUID> analyzed = repositories.saved(userId, users.loginOf(userId)).stream()
                .filter(summary -> summary.lastAnalyzedAt() != null)
                .map(RepositorySummary::publicId)
                .collect(Collectors.toSet());

        return run.getRepos().stream()
                .map(repo -> new AssistantStepRequest.Repo(repo.ref(), repo.name(), analyzed.contains(repo.id()),
                        analyzing.contains(repo.id())))
                .toList();

    }

    private static AssistantStepRequest.DoneStep doneStep(RunItem item) {

        return new AssistantStepRequest.DoneStep(item.seq(), item.tool(), item.ok(), item.error(), find(item.find()),
                item.postingRef(), item.repoRef(), postings(item.postings()), item.totalFound(), match(item.match()),
                item.questionCount());

    }

    private static AssistantStepRequest.Find find(RunItem.Find find) {

        return find == null ? null : new AssistantStepRequest.Find(find.company(), find.keywords());

    }

    private static List<AssistantStepRequest.Posting> postings(List<RunItem.Posting> postings) {

        if (postings == null) {
            return List.of();
        }

        return postings.stream()
                .map(posting -> new AssistantStepRequest.Posting(posting.ref(), posting.company(), posting.title(),
                        posting.daysLeft(), posting.requiredMet(), posting.requiredTotal()))
                .toList();

    }

    private static AssistantStepRequest.Match match(RunItem.Match match) {

        return match == null ? null : new AssistantStepRequest.Match(match.requiredMet(), match.requiredTotal(),
                match.met(), match.missing());

    }
}
