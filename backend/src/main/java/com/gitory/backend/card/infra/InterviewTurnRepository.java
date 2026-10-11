package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.interview.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

    List<InterviewTurn> findByCardIdOrderBySeqAsc(Long cardId);
}
