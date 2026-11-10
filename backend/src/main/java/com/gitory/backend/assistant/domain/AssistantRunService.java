package com.gitory.backend.assistant.domain;

import com.gitory.backend.assistant.infra.AssistantRunRepository;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.ingest.domain.RepositoryListService;
import com.gitory.backend.ingest.domain.RepositorySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 실행을 만들고, 물음에 답하는 말을 더하고, 내 실행을 읽는다
 * 실행을 돌리는 일은 워커가 하고, 여기서는 RUNNING 으로 접수하기까지만 한다
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantRunService {

    private static final int TEXT_MAX_LENGTH = 500;
    private static final int MAX_REPOS = 30;
    private static final String REPO_REF_PREFIX = "r";

    private final AssistantRunRepository runs;
    private final AssistantProperties properties;
    private final RepositoryListService repositories;
    private final UserRateLimiter rateLimiter;
    private final AuditLog auditLog;

    /** 입력 검사를 통과한 뒤에만 호출 제한을 세고, 저장한 레포 중 최근 것 30개에 이름표를 붙여 실행에 고정한다 */
    public AssistantRunView start(Long userId, String login, String text) {

        String said = checkedText(text);
        rateLimiter.acquire(RateLimitedAction.ASSISTANT, userId, 1);

        return AssistantRunView.of(runs.save(AssistantRun.start(userId, said, properties.stepLimit(),
                repoLabels(userId, login))));

    }

    /** 물음을 기다리는 실행에만 더하며, 입력과 상태 검사를 통과한 뒤에만 호출 제한을 센다 */
    @Transactional
    public AssistantRunView say(Long userId, UUID runId, String text) {

        AssistantRun run = runs.findWithLockByPublicIdAndUserId(runId, userId)
                .orElseThrow(() -> notFound(runId, userId, AuditAction.EDIT_ASSISTANT_RUN));
        String said = checkedText(text);
        if (run.getState() != AssistantRunState.WAITING_USER) {
            throw new AssistantRunStateException("물음에 답할 수 있는 상태가 아닙니다.");
        }
        if (run.userMessageCount() >= AssistantRun.MAX_USER_MESSAGES) {
            throw new AssistantRunStateException(
                    "한 실행에 적을 수 있는 말은 " + AssistantRun.MAX_USER_MESSAGES + "개까지입니다.");
        }
        rateLimiter.acquire(RateLimitedAction.ASSISTANT, userId, 1);

        run.addUserMessage(said);

        return AssistantRunView.of(run);

    }

    @Transactional(readOnly = true)
    public AssistantRunView get(Long userId, UUID runId) {

        return AssistantRunView.of(runs.findByPublicIdAndUserId(runId, userId)
                .orElseThrow(() -> notFound(runId, userId, AuditAction.VIEW_ASSISTANT_RUN)));

    }

    /** 남의 실행이면 거절 기록을 남기되, 응답은 없는 실행과 똑같이 한다 */
    private AssistantRunNotFoundException notFound(UUID runId, Long userId, AuditAction action) {

        runs.findByPublicId(runId).ifPresent(others -> auditLog.denied(userId, action, others.getId()));

        return new AssistantRunNotFoundException();

    }

    private List<RepoLabel> repoLabels(Long userId, String login) {

        List<RepositorySummary> recent = repositories.saved(userId, login).stream()
                .sorted(Comparator.comparing(RepositorySummary::activeTo,
                                Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                        .thenComparing(RepositorySummary::name))
                .limit(MAX_REPOS)
                .toList();
        List<RepoLabel> labels = new ArrayList<>();
        for (int index = 0; index < recent.size(); index++) {
            RepositorySummary repository = recent.get(index);
            labels.add(new RepoLabel(REPO_REF_PREFIX + (index + 1), repository.publicId(), repository.name()));
        }

        return labels;

    }

    // DB 가 U+0000 을 저장하지 못해, 그대로 두면 저장 때 500 이 된다
    private static String checkedText(String text) {

        String said = text == null ? "" : text.strip();
        if (said.isEmpty()) {
            throw new InvalidAssistantInputException("할 일을 적어 주세요.");
        }
        if (said.indexOf('\u0000') >= 0) {
            throw new InvalidAssistantInputException("할 일에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        if (said.length() > TEXT_MAX_LENGTH) {
            throw new InvalidAssistantInputException("할 일은 " + TEXT_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        return said;

    }
}
