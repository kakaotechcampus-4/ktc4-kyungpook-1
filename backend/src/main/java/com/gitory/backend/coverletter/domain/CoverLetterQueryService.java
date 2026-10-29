package com.gitory.backend.coverletter.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CoverLetterQueryService {

    private final CoverLetterRepository letters;
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
}
