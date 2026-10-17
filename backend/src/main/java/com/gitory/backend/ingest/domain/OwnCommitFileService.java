package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.OwnCommitFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 다른 모듈이 내 저장소 연결과 연결 하나에서 내 커밋이 바꾼 파일을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class OwnCommitFileService {

    private final OwnCommitFileRepository ownCommitFiles;
    private final StoredActivityService storedActivity;

    /** 바뀐 파일을 아직 채우지 않은 커밋은 파일이 없어 결과에 나오지 않는다 */
    @Transactional(readOnly = true)
    public List<OwnCommitFile> findFiles(Long userRepositoryId, String login) {

        return ownCommitFiles.findOwnCommitFiles(userRepositoryId, login);

    }

    @Transactional(readOnly = true)
    public List<Long> connectionIdsOf(Long userId) {

        return ownCommitFiles.findConnectionIds(userId);

    }

    /** 고른 sha 중 이 저장소 연결의 내 커밋만 바뀐 파일과 함께 돌려주며, 내 것이 아닌 sha 는 결과에 없다 */
    @Transactional(readOnly = true)
    public List<StoredCommit> findOwnCommits(Long userRepositoryId, String login, Collection<String> shas) {

        Set<Long> ownIds = new HashSet<>(ownCommitFiles.findOwnCommitIds(userRepositoryId, login, shas));

        return storedActivity.findCommits(userRepositoryId, shas).stream()
                .filter(commit -> ownIds.contains(commit.id()))
                .toList();

    }
}
