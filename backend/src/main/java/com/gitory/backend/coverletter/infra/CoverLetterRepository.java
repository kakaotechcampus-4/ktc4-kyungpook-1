package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CoverLetter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverLetterRepository extends JpaRepository<CoverLetter, Long> {
}
