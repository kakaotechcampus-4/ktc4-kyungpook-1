package com.gitory.backend.recommend.infra;

import com.gitory.backend.recommend.domain.Candidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    List<Candidate> findByUserRepositoryId(Long userRepositoryId);

    // 후보를 잠그는 다른 작업(카드 만들기 등)도 id 순으로 잠가야, 둘이 서로 상대가 잡은 행을 기다리며 멈추지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Candidate> findWithLockByUserRepositoryIdOrderById(Long userRepositoryId);

    long countByUserRepositoryId(Long userRepositoryId);
}
