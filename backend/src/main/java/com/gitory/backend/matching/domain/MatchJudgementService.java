package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.JudgeCard;
import com.gitory.backend.agent.port.JudgeCondition;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.matching.infra.MatchJudgementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공고 조건마다 그 조건을 채우는 카드를 AI 에게 묻고 (카드, 카드 내용 해시, 조건)마다 저장해, 같은 카드 글로는 다시 묻지 않는다
 * AI 는 부르는 동안 DB 커넥션을 잡지 않도록 트랜잭션 밖에서 부르고, 저장만 트랜잭션으로 묶는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchJudgementService {

    // AI 판단 API 가 한 번에 받는 상한이다
    static final int MAX_CONDITIONS = 30;
    static final int MAX_CARDS = 40;

    private final MatchJudgementRepository judgements;
    private final MatchJudgementPort port;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /**
     * criteria 마다 그 조건을 채운 카드와 이유를 카드 id 순으로 돌려주며, 채운 카드가 없는 조건은 빈 목록이다
     * 아직 판단하지 않은 짝을 AI 에게서 받지 못하면 아무것도 저장하지 않고 빈 Optional 을 돌려준다
     */
    public Optional<Map<Long, List<JudgedSupport>>> judge(JobPosting posting, List<JobPostingCriterion> criteria,
                                                          List<MatchingCard> cards) {

        Map<Long, String> hashes = cards.stream()
                .collect(Collectors.toMap(MatchingCard::id, CardContentHash::of));
        Map<Pair, MatchJudgement> stored = current(criteria, cards, hashes);

        List<MatchingCard> unjudgedCards = cards.stream()
                .filter(card -> criteria.stream().anyMatch(c -> !stored.containsKey(new Pair(card.id(), c.getId()))))
                .toList();
        List<JobPostingCriterion> unjudgedCriteria = criteria.stream()
                .filter(c -> cards.stream().anyMatch(card -> !stored.containsKey(new Pair(card.id(), c.getId()))))
                .toList();

        Map<Pair, String> asked = new HashMap<>();
        if (!unjudgedCards.isEmpty()) {
            try {
                asked.putAll(ask(posting, unjudgedCriteria, unjudgedCards));
            } catch (AgentUnavailableException failure) {
                log.warn("공고 {} 의 조건 판단을 AI 에게서 받지 못해 이 공고는 판단하지 않는다", posting.getId(), failure);
                return Optional.empty();
            }
            save(unjudgedCards, unjudgedCriteria, hashes, asked);
        }

        Map<Long, List<JudgedSupport>> supports = new LinkedHashMap<>();
        for (JobPostingCriterion criterion : criteria) {
            supports.put(criterion.getId(), cards.stream()
                    .sorted(Comparator.comparing(MatchingCard::id))
                    .map(card -> support(new Pair(card.id(), criterion.getId()), stored, asked))
                    .flatMap(Optional::stream)
                    .toList());
        }
        return Optional.of(supports);

    }

    // 상한을 넘으면 카드와 조건을 나눠 묻고, 채운 짝만 이유와 함께 모은다
    private Map<Pair, String> ask(JobPosting posting, List<JobPostingCriterion> criteria, List<MatchingCard> cards) {

        Map<Pair, String> met = new HashMap<>();
        for (List<JobPostingCriterion> criteriaPart : chunks(criteria, MAX_CONDITIONS)) {
            for (List<MatchingCard> cardPart : chunks(cards, MAX_CARDS)) {
                List<JudgedCondition> judged = port.judge(request(posting, criteriaPart, cardPart));
                for (JudgedCondition condition : judged) {
                    for (JudgedCondition.ConditionSupport support : condition.metBy()) {
                        met.put(new Pair(Long.valueOf(support.cardId()), Long.valueOf(condition.conditionId())),
                                support.reason());
                    }
                }
            }
        }
        return met;

    }

    // 못 채운 짝도 남겨야 같은 카드 글로 다시 묻지 않는다
    private void save(List<MatchingCard> cards, List<JobPostingCriterion> criteria, Map<Long, String> hashes,
                      Map<Pair, String> met) {

        Instant now = Instant.now(clock);
        transaction.executeWithoutResult(status -> {
            for (MatchingCard card : cards) {
                String hash = hashes.get(card.id());
                judgements.deleteOtherHashes(card.id(), hash);
                for (JobPostingCriterion criterion : criteria) {
                    String reason = met.get(new Pair(card.id(), criterion.getId()));
                    judgements.insertIfAbsent(card.id(), hash, criterion.getId(), reason != null, reason, now);
                }
            }
        });

    }

    private static Optional<JudgedSupport> support(Pair pair, Map<Pair, MatchJudgement> stored, Map<Pair, String> asked) {

        MatchJudgement judgement = stored.get(pair);
        if (judgement != null) {
            return judgement.isMet() ? Optional.of(new JudgedSupport(pair.cardId(), judgement.getReason()))
                    : Optional.empty();
        }
        return Optional.ofNullable(asked.get(pair)).map(reason -> new JudgedSupport(pair.cardId(), reason));

    }

    private static MatchJudgementRequest request(JobPosting posting, List<JobPostingCriterion> criteria,
                                                 List<MatchingCard> cards) {

        return new MatchJudgementRequest(posting.getCompanyName(), posting.getTitle(),
                criteria.stream()
                        .map(c -> new JudgeCondition(String.valueOf(c.getId()),
                                PostingCriterionType.valueOf(c.getType().name()), c.getText()))
                        .toList(),
                cards.stream()
                        .map(card -> new JudgeCard(String.valueOf(card.id()), card.title(), card.situation(),
                                card.task(), card.action(), card.result(),
                                card.techs().stream().map(CardTech::name).toList()))
                        .toList());

    }

    private static Set<Long> ids(List<JobPostingCriterion> criteria) {

        return criteria.stream().map(JobPostingCriterion::getId).collect(Collectors.toCollection(HashSet::new));

    }

    private static <T> List<List<T>> chunks(List<T> items, int size) {

        List<List<T>> parts = new ArrayList<>();
        for (int start = 0; start < items.size(); start += size) {
            parts.add(items.subList(start, Math.min(items.size(), start + size)));
        }
        return parts;

    }

    /** 저장된 판단만 읽고 AI 는 부르지 않으며, 지금 카드 글로 아직 판단하지 않은 카드가 남은 조건은 complete 가 false 다 */
    public Map<Long, StoredJudgement> stored(List<JobPostingCriterion> criteria, List<MatchingCard> cards) {

        Map<Long, String> hashes = cards.stream()
                .collect(Collectors.toMap(MatchingCard::id, CardContentHash::of));
        Map<Pair, MatchJudgement> current = current(criteria, cards, hashes);
        List<MatchingCard> byId = cards.stream().sorted(Comparator.comparing(MatchingCard::id)).toList();
        Map<Long, StoredJudgement> stored = new LinkedHashMap<>();
        for (JobPostingCriterion criterion : criteria) {
            List<JudgedSupport> metBy = byId.stream()
                    .map(card -> support(new Pair(card.id(), criterion.getId()), current, Map.of()))
                    .flatMap(Optional::stream)
                    .toList();
            boolean complete = byId.stream()
                    .allMatch(card -> current.containsKey(new Pair(card.id(), criterion.getId())));
            stored.put(criterion.getId(), new StoredJudgement(metBy, complete));
        }
        return stored;

    }

    // 카드 글이 바뀐 뒤의 판단은 쓰지 않도록, 지금 카드 글의 해시로 저장된 판단만 (카드, 조건) 짝마다 모은다
    private Map<Pair, MatchJudgement> current(List<JobPostingCriterion> criteria, List<MatchingCard> cards,
                                              Map<Long, String> hashes) {

        Map<Pair, MatchJudgement> current = new HashMap<>();
        if (!cards.isEmpty() && !criteria.isEmpty()) {
            judgements.findByCardIdInAndCriterionIdIn(hashes.keySet(), ids(criteria)).stream()
                    .filter(judgement -> judgement.getCardHash().equals(hashes.get(judgement.getCardId())))
                    .forEach(judgement -> current.put(new Pair(judgement.getCardId(), judgement.getCriterionId()),
                            judgement));
        }
        return current;

    }

    private record Pair(Long cardId, Long criterionId) {
    }
}
