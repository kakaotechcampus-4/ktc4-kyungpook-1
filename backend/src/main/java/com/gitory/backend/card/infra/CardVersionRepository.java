package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.CardVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CardVersionRepository extends JpaRepository<CardVersion, Long> {

    Optional<CardVersion> findByCardIdAndVersionNo(Long cardId, short versionNo);

    List<CardVersion> findByCardIdOrderByVersionNoDesc(Long cardId);
}
