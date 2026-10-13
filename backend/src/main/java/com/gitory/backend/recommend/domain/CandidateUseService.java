package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카드로 만들 후보를 잠그고 모두 새 후보일 때만 카드로 쓴 상태로 바꾼다
 * 후보를 잠그는 요청마다 id 순으로 잠그므로 겹치는 요청끼리, 그리고 다시 분석의 저장과 서로 기다리며 멈추지 않는다
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CandidateUseService {

    private final CandidateRepository candidates;
    private final CandidateCommitRepository candidateCommits;
    private final RepositoryOwnershipService ownership;
    private final AuditLog auditLog;

    /**
     * 받은 공개 id 순서대로 돌려주며, null 은 형식이 잘못된 id 라 없는 후보와 같게 본다
     * 이 저장소 후보가 아닌 id 가 하나라도 있으면 아무것도 바꾸지 않고 찾을 수 없다고 답하되, 남의 저장소 후보면 거절 기록을 남긴다
     */
    public List<CandidateForCard> useForCards(Long userId, Long userRepositoryId, List<UUID> publicIds) {

        Map<UUID, Candidate> locked = candidates.findWithLockByUserRepositoryIdAndPublicIdInOrderById(
                        userRepositoryId, publicIds.stream().filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Candidate::getPublicId, Function.identity()));

        if (publicIds.stream().anyMatch(id -> !locked.containsKey(id))) {
            denyOthers(userId, publicIds, locked.keySet());
            throw new CandidateNotFoundException();
        }

        if (locked.values().stream().anyMatch(candidate -> candidate.getStatus() != CandidateStatus.NEW)) {
            throw new CandidateNotUsableException();
        }

        Set<Long> withCommits = candidateCommits.findByCandidateIdInAndIncludedTrue(
                        locked.values().stream().map(Candidate::getId).toList())
                .stream()
                .map(CandidateCommit::getCandidateId)
                .collect(Collectors.toSet());
        locked.values().forEach(Candidate::use);

        return publicIds.stream()
                .map(locked::get)
                .map(candidate -> new CandidateForCard(candidate.getId(), candidate.getTitle(),
                        withCommits.contains(candidate.getId()), taskTextOf(candidate)))
                .toList();

    }

    // 직접 추가하거나 회상으로 만든 후보만 사용자가 쓴 글이 이유 칸에 있어 과제(T) 칸에 옮긴다
    private static String taskTextOf(Candidate candidate) {

        return candidate.getSourceType() == CandidateType.MANUAL ? candidate.getReasonText() : null;

    }

    private void denyOthers(Long userId, List<UUID> publicIds, Set<UUID> found) {

        List<UUID> missing = publicIds.stream().filter(id -> id != null && !found.contains(id)).toList();
        if (missing.isEmpty()) {
            return;
        }

        for (Candidate candidate : candidates.findByPublicIdIn(missing)) {
            boolean others = ownership.ownerOf(candidate.getUserRepositoryId())
                    .map(owner -> !owner.equals(userId))
                    .orElse(false);
            if (others) {
                auditLog.denied(userId, AuditAction.CREATE_CARD, candidate.getId());
            }
        }

    }
}
