package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 본인 Job 을 취소하고, 취소된(또는 이미 끝나 있던) Job 을 돌려준다 */
@Service
@RequiredArgsConstructor
public class JobCancelService {

    private final AnalysisJobRepository jobs;
    private final AuditLog auditLog;

    @Transactional
    public JobView cancel(UUID publicId, Long userId) {

        AnalysisJob job = jobs.findWithLockByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> notFound(publicId, userId));
        job.cancel();
        jobs.flush();

        return JobView.from(job);

    }

    /** 남의 Job 이면 거절 기록을 남기되, 응답은 없는 Job 과 똑같이 한다 */
    private JobNotFoundException notFound(UUID publicId, Long userId) {

        jobs.findByPublicId(publicId)
                .ifPresent(others -> auditLog.denied(userId, AuditAction.CANCEL_JOB, others.getId()));

        return new JobNotFoundException();

    }
}
