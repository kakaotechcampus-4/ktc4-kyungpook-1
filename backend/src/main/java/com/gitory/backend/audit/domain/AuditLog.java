package com.gitory.backend.audit.domain;

import com.gitory.backend.audit.infra.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 기록을 요청 트랜잭션과 따로 커밋하고, 기록에 실패하면 로그만 남겨 요청은 그대로 진행되게 한다
 * 요청 id 는 common.api 의 RequestIdFilter 가 MDC 에 넣은 값을 같은 이름으로 읽는다 — 도메인이 api 를 참조하지 않으려고 MDC 를 거친다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLog {

    private static final String REQUEST_ID = "requestId";

    private final AuditEventRepository events;
    private final PlatformTransactionManager transactionManager;

    /**
     * 요청 트랜잭션 안에서 부르면 커밋된 뒤에 남겨, 롤백된 일은 성공으로 남기지 않는다
     * 첫 로그인처럼 같은 트랜잭션에서 만든 사용자 행은 커밋 전에는 다른 트랜잭션이 참조할 수 없다
     */
    public void ok(Long userId, AuditAction action, Long subjectId) {

        AuditEvent event = AuditEvent.of(userId, action, subjectId, AuditOutcome.OK, MDC.get(REQUEST_ID));

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            save(event);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {

                save(event);

            }
        });

    }

    /** 요청이 404 로 롤백돼도 남도록 기다리지 않고 바로 따로 커밋한다 */
    public void denied(Long userId, AuditAction action, Long subjectId) {

        save(AuditEvent.of(userId, action, subjectId, AuditOutcome.DENIED, MDC.get(REQUEST_ID)));

    }

    private void save(AuditEvent event) {

        TransactionTemplate newTransaction = new TransactionTemplate(transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));

        try {
            newTransaction.executeWithoutResult(status -> events.save(event));
        } catch (RuntimeException failed) {
            log.warn("감사 기록을 남기지 못했다: {} {} 사용자 {} 대상 {} 요청 {}", event.getAction(), event.getOutcome(),
                    event.getUserId(), event.getSubjectId(), event.getRequestId(), failed);
        }

    }
}
