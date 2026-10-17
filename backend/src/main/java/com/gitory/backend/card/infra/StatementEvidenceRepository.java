package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.StatementEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public interface StatementEvidenceRepository extends JpaRepository<StatementEvidence, Long> {

    List<StatementEvidence> findByStatementIdInOrderById(Collection<Long> statementIds);

    default Map<Long, List<Long>> findCommitIdsByStatement(Collection<Long> statementIds) {

        return findByStatementIdInOrderById(statementIds).stream()
                .collect(Collectors.groupingBy(StatementEvidence::getStatementId,
                        Collectors.mapping(StatementEvidence::getCommitId, Collectors.toList())));

    }
}
