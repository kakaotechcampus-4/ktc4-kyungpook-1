package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.domain.OwnCommitFileService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 커밋 메시지가 기억나지 않는 사용자가 만졌던 폴더로 경험을 떠올리게, 저장해 둔 내 커밋의 파일을 폴더별로 묶어 준다 */
@Service
@RequiredArgsConstructor
public class RecallService {

    static final String NOTE = "어떤 파일을 만졌는지로 기억을 끌어냅니다. 답한 내용은 후보가 되고, 그 후보로 만든 카드의 과제(T) 칸에 그대로 들어갑니다.";
    static final String NOTHING_TO_RECALL_NOTE = "바뀐 파일이 저장된 내 커밋이 없어요.";

    private final RepositoryOwnershipService ownership;
    private final OwnCommitFileService ownCommitFiles;

    @Transactional(readOnly = true)
    public Recall load(UUID repositoryId, Long userId, String login) {

        Long userRepositoryId = ownership.findOwnedRepository(repositoryId, userId, AuditAction.VIEW_RECALL)
                .orElseThrow(CandidateBoardNotFoundException::new);
        List<RecallDir> dirs = RecallDirs.of(ownCommitFiles.findFiles(userRepositoryId, login));

        return new Recall(repositoryId, dirs.isEmpty() ? NOTHING_TO_RECALL_NOTE : NOTE, dirs);

    }
}
