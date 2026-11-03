package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CoverLetterTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoverLetterTurnRepository extends JpaRepository<CoverLetterTurn, Long> {

    List<CoverLetterTurn> findByCoverLetterIdOrderBySeq(Long coverLetterId);
}
