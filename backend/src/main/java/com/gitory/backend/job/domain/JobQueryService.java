package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final AnalysisJobRepository jobs;
    private final AuditLog auditLog;

    @Transactional(readOnly = true)
    public JobView load(UUID publicId, Long userId) {

        return jobs.findByPublicIdAndUserId(publicId, userId)
                .map(JobView::from)
                .orElseThrow(() -> notFound(publicId, userId));

    }

    @Transactional(readOnly = true)
    public List<ActiveJobRow> loadActive(Long userId) {
        return jobs.findActiveRowsByUserId(userId, JobState.ACTIVE_NAMES);
    }

    /** 남의 Job 이면 거절 기록을 남기되, 응답은 없는 Job 과 똑같이 한다 */
    private JobNotFoundException notFound(UUID publicId, Long userId) {

        jobs.findByPublicId(publicId)
                .ifPresent(others -> auditLog.denied(userId, AuditAction.VIEW_JOB, others.getId()));

        return new JobNotFoundException();

    }
}
