package com.gitory.backend.job.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Set;

/**
 * 서버나 AI 문제로 Job 이 실패하면 팀에 한 줄로 알린다
 * GitHub 쪽 문제와 근거 없음·수집 상한처럼 사용자 데이터에서 생긴 이유는 서버가 고칠 일이 아니라서 알리지 않는다
 */
@Component
@RequiredArgsConstructor
public class JobFailureAlert {

    private static final Set<JobErrorCode> ALERTED =
            EnumSet.of(JobErrorCode.INTERNAL_ERROR, JobErrorCode.AI_UNAVAILABLE, JobErrorCode.DRAFT_TIMEOUT);

    private final JobAlertSender sender;

    // 실패가 롤백되면 알리지 않도록 커밋이 끝난 뒤에 받는다
    @TransactionalEventListener
    public void jobFailed(JobFailed event) {

        if (!ALERTED.contains(event.errorCode())) {
            return;
        }

        sender.send("[Gitory] " + kindOf(event.type()) + " Job " + event.jobId() + " 이 " + event.errorCode()
                + " 로 실패했습니다");

    }

    private static String kindOf(JobType type) {

        return type == JobType.DRAFT ? "카드 초안" : "분석";

    }
}
