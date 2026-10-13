package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.OwnCommitFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 다른 모듈이 저장소 연결 하나에서 내 커밋이 바꾼 파일을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class OwnCommitFileService {

    private final OwnCommitFileRepository ownCommitFiles;

    /** 바뀐 파일을 아직 채우지 않은 커밋은 파일이 없어 결과에 나오지 않는다 */
    @Transactional(readOnly = true)
    public List<OwnCommitFile> findFiles(Long userRepositoryId, String login) {

        return ownCommitFiles.findOwnCommitFiles(userRepositoryId, login);

    }
}
