package com.gitory.backend.matching.domain;

import java.util.Map;
import java.util.UUID;

// candidateRepos 는 후보마다 그 후보가 나온 레포의 공개 id 이며, 화면이 카드 만들기 API 경로에 쓰도록 제안에 싣는다
record GapFillMaterials(GapFillRequest request, Map<UUID, String> draftTitles, Map<UUID, UUID> candidateRepos,
                        String fingerprint) {

    GapFillCondition enrich(GapFillCondition condition) {

        GapFillProposal proposal = condition.proposal();
        if (proposal == null) {
            return condition;
        }
        return condition.withProposal(switch (proposal.action()) {
            case CREATE_CARD -> request.candidates().stream()
                    .filter(candidate -> candidate.candidateId().equals(proposal.targetId()))
                    .findFirst()
                    .map(candidate -> proposal.withTarget(candidate.title(), candidate.repoName(),
                            candidateRepos.get(candidate.candidateId())))
                    .orElse(proposal);
            case CONTINUE_CARD -> proposal.withTarget(draftTitles.get(proposal.targetId()), null, null);
            case ANALYZE_REPO -> request.repos().stream()
                    .filter(repo -> repo.repoId().equals(proposal.targetId()))
                    .findFirst()
                    .map(repo -> proposal.withTarget(repo.fullName(), repo.fullName(), repo.repoId()))
                    .orElse(proposal);
        });

    }
}
