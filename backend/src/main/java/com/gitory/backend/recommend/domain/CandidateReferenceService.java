package com.gitory.backend.recommend.domain;

import com.gitory.backend.recommend.infra.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 다른 모듈이 카드에 보일 후보의 종류·ref·제목과 남은 새 후보 수를 후보 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CandidateReferenceService {

    private final CandidateRepository candidates;

    public Optional<CandidateReference> find(Long candidateId) {

        return candidates.findById(candidateId).map(CandidateReferenceService::referenceOf);

    }

    /** 카드 목록 전체가 후보를 한 번에 읽도록 후보 id 마다 하나씩 묶어 돌려주며, 없는 후보는 빠진다 */
    public Map<Long, CandidateReference> findAll(Collection<Long> candidateIds) {

        return candidates.findAllById(candidateIds).stream()
                .map(CandidateReferenceService::referenceOf)
                .collect(Collectors.toMap(CandidateReference::id, Function.identity()));

    }

    /** 저장소 연결에서 아직 카드로 쓰지도 제외하지도 않은 후보 수다 */
    public int countNew(Long userRepositoryId) {

        return (int) candidates.countByUserRepositoryIdAndStatus(userRepositoryId, CandidateStatus.NEW);

    }

    // 화면이 ref 를 글자로 받아서 AI 가 ref 를 주지 않은 후보는 빈 글자로 둔다
    private static CandidateReference referenceOf(Candidate candidate) {

        String ref = candidate.getSourceRef() == null ? "" : candidate.getSourceRef();

        return new CandidateReference(candidate.getId(), candidate.getPublicId(), candidate.getSourceType(), ref,
                candidate.getTitle());

    }
}
