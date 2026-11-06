package com.gitory.backend.job.infra;

import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.job.domain.ActiveJobRow;
import com.gitory.backend.job.domain.JobIntakeService;
import com.gitory.backend.job.domain.JobQueryService;
import com.gitory.backend.job.domain.JobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * assistant 는 job 을 참조할 수 없으므로, 분석 요청 API 와 같은 확인(소유·GitHub 연결)과 접수를 job 이 assistant 의 포트로 이어 준다
 * 이미 도는 분석 Job 이 있으면 접수가 새로 만들지 않고 그 Job 을 돌려준다
 */
@Component
@RequiredArgsConstructor
public class AssistantRepoAnalysisAdapter implements RepoAnalysisPort {

    private final RepositoryOwnershipService ownership;
    private final GithubCollectionAccessPort githubAccess;
    private final JobIntakeService jobIntake;
    private final JobQueryService jobQuery;

    @Override
    public UUID start(Long userId, UUID repoId) {

        Long userRepositoryId = ownership.findOwnedRepository(repoId, userId, AuditAction.ANALYZE)
                .orElseThrow(RepoNotFoundException::new);
        githubAccess.requireUsableConnection(userId);

        return jobIntake.intake(userId, userRepositoryId, null).jobId();

    }

    @Override
    public Set<UUID> analyzingRepoIds(Long userId) {

        return jobQuery.loadActive(userId).stream()
                .filter(row -> JobType.ANALYZE.name().equals(row.type()))
                .map(ActiveJobRow::userRepositoryId)
                .collect(Collectors.toSet());

    }
}
