package com.gitory.backend.ingest.port;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 저장소 목록·상세와 커밋 찾기가 후보·카드 쪽의 숫자와 id, 후보를 묶은 시각을 읽는 인터페이스다
 * ingest 가 아래 모듈이라 위쪽의 숫자를 거꾸로 읽는 곳은 이 인터페이스를 두고 job.infra 가 구현한다
 * 저장소는 모두 user_repository 의 내부 id 로 가리키고, 값이 없는 저장소는 결과 맵에 없다
 */
public interface RepositoryCandidateCardPort {

    Map<Long, Integer> candidateCounts(Collection<Long> connectionIds);

    Map<Long, Instant> groupedAt(Collection<Long> connectionIds);

    Map<Long, Integer> cardCounts(Collection<Long> connectionIds);

    Map<Long, UUID> candidateIdsByCommit(Long connectionId, Collection<Long> commitIds);
}
