package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CoverLetterQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoverLetterQuestionRepository extends JpaRepository<CoverLetterQuestion, Long> {

    List<CoverLetterQuestion> findByCoverLetterIdOrderBySeq(Long coverLetterId);
}
