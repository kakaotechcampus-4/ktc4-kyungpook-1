package com.gitory.backend.consent.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.consent.port.AccountRepositoryCleanupPort;
import com.gitory.backend.consent.port.ActiveWorkPort;
import lombok.RequiredArgsConstructor;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 사용자와 그 데이터를 바로 지운다
 * 돌고 있는 일이 있으면 지우지 않고 거절하며, GitHub 권한 삭제가 실패해도 삭제는 계속한다
 */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final List<ActiveWorkPort> activeWorks;
    private final GithubDisconnectService githubDisconnect;
    private final AccountRepositoryCleanupPort repositories;
    private final UserRepository users;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public void delete(Long userId) {

        if (activeWorks.stream().anyMatch(work -> work.isRunning(userId))) {
            throw new ActiveWorkRunningException();
        }

        githubDisconnect.disconnect(userId);

        transaction.executeWithoutResult(status -> remove(userId));

        sessions.findByPrincipalName(String.valueOf(userId)).keySet().forEach(sessions::deleteById);

    }

    // 감사 기록은 사용자 행이 사라진 뒤 커밋되므로 user_id 를 처음부터 비우고 지운 사용자 id 는 subject_id 에 남긴다
    private void remove(Long userId) {

        List<Long> repositoryIds = repositories.repositoryIdsOf(userId);

        auditLog.ok(null, AuditAction.DELETE_ACCOUNT, userId);
        users.deleteRow(userId);
        repositories.deleteUnlinked(repositoryIds);

    }
}
