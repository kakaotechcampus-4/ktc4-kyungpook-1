package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.CardDraftStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardDraftStepRepository extends JpaRepository<CardDraftStep, Long> {

    List<CardDraftStep> findByCardIdAndVersionNoOrderBySeq(Long cardId, short versionNo);
}
