package com.gitory.backend.coverletter.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CoverLetterQueryService {

    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final AuditLog auditLog;
    private final CoverLetterViewFactory views;

    /** 남의 자소서면 거절 기록을 남기되, 응답은 없는 자소서와 똑같이 한다 */
    @Transactional(readOnly = true)
    public CoverLetterView load(UUID publicId, Long userId) {

        CoverLetter letter = letters.findByPublicIdAndUserId(publicId, userId).orElseThrow(() -> {
            letters.findByPublicId(publicId)
                    .ifPresent(others -> auditLog.denied(userId, AuditAction.VIEW_COVER_LETTER, others.getId()));
            return new CoverLetterNotFoundException();
        });

        return views.of(letter);

    }

    /** 최근에 만든 자소서부터, 문항 수와 첫 문항으로 어느 자소서인지 알아보게 한다 */
    @Transactional(readOnly = true)
    public List<CoverLetterSummary> list(Long userId) {

        List<CoverLetter> mine = letters.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        if (mine.isEmpty()) {
            return List.of();
        }

        Map<Long, List<CoverLetterQuestion>> byLetter = questions
                .findByCoverLetterIdInOrderBySeq(mine.stream().map(CoverLetter::getId).toList()).stream()
                .collect(Collectors.groupingBy(CoverLetterQuestion::getCoverLetterId));
        return mine.stream().map(letter -> summary(letter, byLetter.get(letter.getId()))).toList();

    }

    private static CoverLetterSummary summary(CoverLetter letter, List<CoverLetterQuestion> loaded) {

        return new CoverLetterSummary(letter.getPublicId(), letter.getStage(), letter.getJobRole(), loaded.size(),
                loaded.getFirst().getText(), letter.getCreatedAt(), letter.getUpdatedAt());

    }
}
