package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 회상 도우미에서 폴더마다 쓴 답을 직접 추가 후보로 저장한다
 * 답은 받은 그대로 후보의 이유가 되고 묶은 기록이나 다른 후보 행은 건드리지 않아, 다시 분석이 저장하는 동안에도 기다리지 않는다
 */
@Service
@RequiredArgsConstructor
public class RecallAnswerService {

    static final String SOURCE_REF = "RECALL";

    private final RepositoryOwnershipService ownership;
    private final CandidateRepository candidates;

    /** 같은 폴더와 같은 답으로 만든 새 후보가 이미 있으면 또 만들지 않고 그 후보를 돌려준다 */
    @Transactional
    public BoardCandidate answer(UUID repositoryId, Long userId, String path, String text) {

        Long userRepositoryId = ownership.findOwnedRepository(repositoryId, userId, AuditAction.CREATE_CANDIDATE)
                .orElseThrow(CandidateBoardNotFoundException::new);
        CandidateText.require(path, "path", RecallDirs.MAX_PATH_LENGTH);
        CandidateText.require(text, "text", CandidateText.REASON_MAX_LENGTH);
        String title = path + RecallDirs.TITLE_SUFFIX;
        Candidate candidate = candidates
                .findFirstByUserRepositoryIdAndSourceTypeAndSourceRefAndTitleAndReasonTextAndStatusOrderById(
                        userRepositoryId, CandidateType.MANUAL, SOURCE_REF, title, text, CandidateStatus.NEW)
                .orElseGet(() -> candidates.save(Candidate.manual(userRepositoryId, SOURCE_REF, title, text, CandidateMeta.of(List.of()))));

        return CandidateBoardService.boardCandidateOf(candidate, List.of(), Map.of(), Optional.empty(), null);

    }
}
