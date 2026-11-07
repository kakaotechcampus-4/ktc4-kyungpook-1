package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.PostingAlertRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingAlertRunRepository extends JpaRepository<PostingAlertRun, Integer> {
}
