package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.card.infra.StatementEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 카드 버전을 만들고 문장을 근거 커밋 연결까지 다른 버전으로 복사한다
 * 새 버전 번호는 카드의 현재 번호에 1 을 더한 값이라, 카드 행을 잠근 요청 트랜잭션 안에서만 부를 수 있다
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CardVersionWriter {

    private final CardVersionRepository versions;
    private final CardStatementRepository statements;
    private final StatementEvidenceRepository evidence;

    /** 막 저장한 카드의 1번 버전을 빈 칸으로 만든다 */
    public CardVersion startFirst(Card card, VersionSource source) {

        return versions.save(CardVersion.of(card.getId(), card.getCurrentVersion(), source));

    }

    /** 다음 번호의 빈 버전을 만들어 카드의 현재 버전으로 삼는다 */
    public CardVersion startNext(Card card, VersionSource source) {

        short next = (short) (card.getCurrentVersion() + 1);
        card.switchTo(next);

        return versions.save(CardVersion.of(card.getId(), next, source));

    }

    /** 다음 번호의 버전을 만들어 from 버전의 네 칸을 그대로 복사하고, from 버전이 없으면 빈 버전을 만들지 않고 404 로 답하게 한다 */
    public CardVersion copyToNext(Card card, short fromVersionNo, VersionSource source) {

        if (versions.findByCardIdAndVersionNo(card.getId(), fromVersionNo).isEmpty()) {
            throw new CardVersionNotFoundException();
        }

        List<CardStatement> sources = statements.findByCardIdAndVersionNo(card.getId(), fromVersionNo);
        CardVersion next = startNext(card, source);
        copy(sources, next.getVersionNo());

        return next;

    }

    /** from 버전의 그 칸에 문장이 없으면 아무것도 복사하지 않는다 */
    public void copySlot(Long cardId, short fromVersionNo, StarField slot, short toVersionNo) {

        copy(statements.findByCardIdAndVersionNoAndStarSlot(cardId, fromVersionNo, slot), toVersionNo);

    }

    private void copy(List<CardStatement> sources, short toVersionNo) {

        Map<Long, List<Long>> commitIds = evidence.findCommitIdsByStatement(
                sources.stream().map(CardStatement::getId).toList());

        for (CardStatement source : sources) {
            CardStatement copied = statements.save(source.copyTo(toVersionNo));
            for (Long commitId : commitIds.getOrDefault(source.getId(), List.of())) {
                evidence.save(StatementEvidence.link(copied.getId(), commitId));
            }
        }

    }
}
