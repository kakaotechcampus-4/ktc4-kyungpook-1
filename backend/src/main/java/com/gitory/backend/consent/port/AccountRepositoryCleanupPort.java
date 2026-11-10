package com.gitory.backend.consent.port;

import java.util.List;

/** 저장소 행은 사용자 사이에 공유되어 사용자 삭제가 지우지 못하므로, 연결이 모두 끊긴 것만 ingest 가 따로 지운다 */
public interface AccountRepositoryCleanupPort {

    List<Long> repositoryIdsOf(Long userId);

    /** 사용자 삭제 뒤에 불러야 하며, 다른 사용자가 아직 연결한 저장소는 건드리지 않는다 */
    void deleteUnlinked(List<Long> repositoryIds);
}
