package com.gitory.backend.assistant.port;

import java.util.Set;
import java.util.UUID;

/** 레포 정리 시작과 정리 중인 레포는 job 만 알아서, assistant 가 job 을 읽지 않고 이 인터페이스로 받는다 */
public interface RepoAnalysisPort {

    /**
     * 분석 요청 API 와 같은 확인(소유·GitHub 연결)을 거쳐 레포의 정리를 시작하고 그 Job 의 공개 id 를 돌려준다
     * 이미 도는 정리가 있으면 새로 만들지 않고 그 Job 을 돌려주며, 내 레포가 아니면 RepoNotFoundException 이다
     */
    UUID start(Long userId, UUID repoId);

    Set<UUID> analyzingRepoIds(Long userId);
}
