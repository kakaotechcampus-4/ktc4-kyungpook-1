package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialReader;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.MatchCondition;
import com.gitory.backend.matching.domain.MatchConditionStatus;
import com.gitory.backend.matching.domain.MatchDetail;
import com.gitory.backend.matching.domain.MatchDetailService;
import com.gitory.backend.matching.domain.MatchPostingNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 공고 상세의 지금 판단을 자소서에 남길 공고 정보로 옮긴다
 * 길이·개수 상한은 AI 자소서 계약(ai/schemas/cover_letter.py 의 Posting)과 같아야 하고, 넘으면 AI 가 요청을 거절한다
 */
@Component
@RequiredArgsConstructor
class CoverLetterPostingReader {

    private static final int MAX_COMPANY = 100;
    private static final int MAX_TITLE = 300;
    private static final int MAX_TEXT = 500;
    private static final int MAX_RESPONSIBILITIES = 30;
    private static final int MAX_CONDITIONS = 60;

    private final MatchDetailService matchDetails;
    private final CardMaterialReader cardMaterials;

    /** 학력·병역 같은 일반 조건은 인적사항으로 따로 가므로 빼고, 판단하지 않은 조건은 상태 없이 남긴다 */
    CoverLetterPosting read(UUID postingId, Long userId) {

        MatchDetail detail;
        try {
            detail = matchDetails.detail(postingId, userId);
        } catch (MatchPostingNotFoundException e) {
            throw new CoverLetterPostingNotFoundException();
        }

        // 판단은 카드 공개 id 로 오는데 AI 는 카드 고르기에 보낸 내부 id 로 카드를 가리킨다
        Map<UUID, Long> cardIds = cardMaterials.read(userId, CoverLetterAssignStep.MAX_CARDS).stream()
                .collect(Collectors.toMap(CardMaterial::publicId, CardMaterial::id));
        List<String> responsibilities = detail.conditions().stream()
                .filter(condition -> condition.type() == CriterionType.RESPONSIBILITY)
                .map(condition -> clip(condition.text(), MAX_TEXT))
                .limit(MAX_RESPONSIBILITIES)
                .toList();
        List<CoverLetterPosting.Condition> conditions = detail.conditions().stream()
                .filter(condition -> condition.type() != CriterionType.RESPONSIBILITY && condition.generalType() == null)
                .map(condition -> conditionOf(condition, cardIds))
                .limit(MAX_CONDITIONS)
                .toList();

        return new CoverLetterPosting(detail.target().id(), clip(detail.target().company(), MAX_COMPANY),
                clip(detail.target().role(), MAX_TITLE), responsibilities, conditions);

    }

    private static CoverLetterPosting.Condition conditionOf(MatchCondition condition, Map<UUID, Long> cardIds) {

        boolean met = condition.status() == MatchConditionStatus.MET;
        String status = met || condition.status() == MatchConditionStatus.NOT_MET ? condition.status().name() : null;
        List<Long> metBy = !met ? List.of() : condition.metBy().stream()
                .map(card -> cardIds.get(card.cardId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return new CoverLetterPosting.Condition(condition.type().name(), clip(condition.text(), MAX_TEXT), status, metBy);

    }

    private static String clip(String text, int limit) {

        return text.length() <= limit ? text : text.substring(0, limit);

    }
}
