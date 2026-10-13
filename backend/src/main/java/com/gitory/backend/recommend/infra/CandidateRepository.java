package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.Candidate;
import com.gitory.backend.recommend.domain.CandidateStatus;
import com.gitory.backend.recommend.domain.CandidateType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    List<Candidate> findByUserRepositoryId(Long userRepositoryId);

    // 후보를 잠그는 다른 작업(카드 만들기 등)도 id 순으로 잠가야, 둘이 서로 상대가 잡은 행을 기다리며 멈추지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Candidate> findWithLockByUserRepositoryIdOrderById(Long userRepositoryId);

    long countByUserRepositoryId(Long userRepositoryId);

    // 같은 글을 다시 보내도 후보가 늘지 않게 하는 조회로, 행을 잠그지 않아 다시 분석 저장과 서로 기다리지 않는다
    Optional<Candidate> findFirstByUserRepositoryIdAndSourceTypeAndSourceRefAndTitleAndReasonTextAndStatusOrderById(
            Long userRepositoryId, CandidateType sourceType, String sourceRef, String title, String reasonText,
            CandidateStatus status);
}
