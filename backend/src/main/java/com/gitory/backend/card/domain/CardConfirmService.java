package com.gitory.backend.card.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.agent.port.CardThemeRequest;
import com.gitory.backend.agent.port.CardThemeSlot;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.CardVersionRepository;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.ingest.domain.RepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.CandidateReference;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 내 카드를 확정하고, 현재 버전을 그대로 복사한 확정본을 새 버전으로 남긴다
 * 응답을 못 받은 화면이 다시 확정해도 성공으로 보이도록, 이미 확정된 카드는 새 버전 없이 지금 확정 결과를 돌려준다
 * 문항 유형이 빈 경험 카드는 AI 에게 유형을 물어 붙이되, 카드 행을 잠근 채 기다리지 않도록 잠그기 전에 묻는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardConfirmService {

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardVersionReader reader;
    private final CardVersionWriter writer;
    private final RepositoryReferenceService repositories;
    private final CandidateReferenceService candidates;
    private final AuditLog auditLog;
    private final CardThemePort themes;
    private final PlatformTransactionManager transactionManager;

    public ConfirmResult confirm(UUID publicId, Long userId) {

        List<CardTheme> suggested = suggestedThemes(publicId, userId);

        return new TransactionTemplate(transactionManager).execute(status -> confirmLocked(publicId, userId, suggested));

    }

    // AI 가 늦거나 실패하면 빈 유형으로 확정하고, 확정은 막지 않는다
    private List<CardTheme> suggestedThemes(UUID publicId, Long userId) {

        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        CardThemeRequest request = readOnly.execute(status -> themeRequestOf(publicId, userId));
        if (request == null) {
            return List.of();
        }

        try {
            return AiCallContext.callForCard(request.cardId(), () -> themes.themesFor(request)).stream()
                    .map(theme -> CardTheme.valueOf(theme.name()))
                    .toList();
        } catch (AgentUnavailableException failure) {
            log.warn("카드 {} 의 문항 유형을 AI 에게서 받지 못해 빈 유형으로 확정한다", request.cardId(), failure);
            return List.of();
        }

    }

    // 이미 확정했거나, 유형이 필요 없거나, 어차피 확정할 수 없는 카드는 AI 를 부르지 않는다
    private CardThemeRequest themeRequestOf(UUID publicId, Long userId) {

        Card card = ownership.findOwned(publicId, userId, AuditAction.CONFIRM_CARD);
        if (card.isConfirmed() || !card.needsThemes()) {
            return null;
        }
        VersionSlots slots = reader.read(card.getId(), card.getCurrentVersion());
        if (!confirmable(slots)) {
            return null;
        }

        return new CardThemeRequest(card.getId(), card.getTitle(), Arrays.stream(StarField.values())
                .map(field -> new CardThemeSlot(InterviewStarSlot.valueOf(field.name()), slots.textOf(field)))
                .toList());

    }

    private ConfirmResult confirmLocked(UUID publicId, Long userId, List<CardTheme> suggested) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.CONFIRM_CARD);

        if (card.isConfirmed()) {
            return resultOf(card);
        }

        // 문장을 고치는 요청은 모두 카드 행을 먼저 잠그므로, 잠근 뒤 읽는 여기서는 문장과 근거가 한 시점으로 읽힌다
        VersionSlots slots = reader.read(card.getId(), card.getCurrentVersion());
        if (!confirmable(slots)) {
            throw new CardNotConfirmableException();
        }

        // 확정은 글을 바꾸지 않으므로 확정본의 출처도 현재 버전 것을 그대로 쓴다
        CardVersion current = versions.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion()).orElseThrow();
        CardVersion confirmed = writer.copyToNext(card, current.getVersionNo(), current.getSource());
        confirmed.markConfirmed();
        card.confirm();
        card.suggestThemes(suggested);
        auditLog.ok(userId, AuditAction.CONFIRM_CARD, card.getId());

        return resultOf(card);

    }

    private static boolean confirmable(VersionSlots slots) {

        return slots.stateOf(StarField.S) != StarFieldState.EMPTY && slots.stateOf(StarField.A) != StarFieldState.EMPTY;

    }

    private ConfirmResult resultOf(Card card) {

        UUID repoId = Optional.ofNullable(card.getUserRepositoryId())
                .flatMap(repositories::find)
                .map(RepositoryReference::publicId)
                .orElse(null);

        List<UUID> usedCandidateIds = Optional.ofNullable(card.getCandidateId())
                .flatMap(candidates::find)
                .map(CandidateReference::publicId)
                .map(List::of)
                .orElse(List.of());
        int remaining = card.getUserRepositoryId() == null ? 0 : candidates.countNew(card.getUserRepositoryId());

        return new ConfirmResult(card.getPublicId(), card.getStatus(), card.getCurrentVersion(), usedCandidateIds,
                remaining, repoId);

    }
}
