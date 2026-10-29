package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CardMaterialAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CardMaterialAnswerRepository extends JpaRepository<CardMaterialAnswer, Long> {

    List<CardMaterialAnswer> findByCardIdIn(Collection<Long> cardIds);
}
