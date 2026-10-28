package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.ActivityRepository;
import com.gitory.backend.card.infra.CardRepository;
import com.gitory.backend.card.infra.InterviewTurnRepository;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.CandidateReference;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 내 카드를 최근에 고친 순으로 읽고, 칸 상태와 개수는 따로 저장하지 않고 현재 버전 문장으로 판정한다 */
@Service
@RequiredArgsConstructor
public class CardListService {

    private final CardRepository cards;
    private final CardVersionReader reader;
    private final CandidateReferenceService candidates;
    private final RepositoryReferenceService repositories;
    private final ActivityRepository activities;
    private final InterviewTurnRepository turns;

    // 임시 저장이 문장을 지우고 다시 넣는 중에도 카드·문장·근거를 한 시점으로 읽는다
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CardSummary> list(Long userId) {

        List<Card> found = cards.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
        Map<Long, VersionSlots> current = reader.readCurrent(found);
        Map<Long, CandidateReference> references = candidates.findAll(found.stream()
                .map(Card::getCandidateId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, RepositoryReference> repos = repositories.findAll(found.stream()
                .map(Card::getUserRepositoryId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, ActivityReference> groups = activities.findAllById(found.stream()
                        .map(Card::getActivityId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Activity::getId, ActivityReference::of));
        Set<Long> interviewed = Set.copyOf(turns.findCardIdsWithAnswer(found.stream().map(Card::getId).toList()));

        return found.stream()
                .map(card -> CardSummary.of(card, current.get(card.getId()), references.get(card.getCandidateId()),
                        repos.get(card.getUserRepositoryId()), groups.get(card.getActivityId()),
                        interviewed.contains(card.getId())))
                .toList();

    }
}
