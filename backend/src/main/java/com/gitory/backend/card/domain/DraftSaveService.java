package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.card.infra.CardSlotNoteRepository;
import com.gitory.backend.card.infra.CardStatementRepository;
import com.gitory.backend.card.infra.CardVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 임시 저장으로 카드의 네 칸을 저장하고, 칸마다 보낸 글이 갈라져 나온 직전 버전의 글과 같으면 그 문장을 근거·칸 메모째 복사한다
 * 비교 기준을 직전 버전으로 둬야 편집 취소로 원문을 다시 보낼 때 커밋 근거와 확인 필요 표시가 함께 돌아온다
 * 확정 카드도 다시 열지 않고 고치며, 저장한 글로 카드 상태를 다시 정한다({@link Card#settle})
 */
@Service
@RequiredArgsConstructor
public class DraftSaveService {

    static final int SLOT_MAX_LENGTH = 1000;

    private final CardOwnershipService ownership;
    private final CardVersionRepository versions;
    private final CardStatementRepository statements;
    private final CardSlotNoteRepository notes;
    private final CardVersionReader reader;
    private final CardVersionWriter writer;
    private final CardThemeSuggester themeSuggester;

    @Transactional
    public SavedDraft save(UUID publicId, Long userId, Map<StarField, String> texts) {

        Card card = ownership.lockOwned(publicId, userId, AuditAction.EDIT_CARD);
        Map<StarField, String> sent = checkedTexts(texts);

        CardVersion current = versions.findByCardIdAndVersionNo(card.getId(), card.getCurrentVersion()).orElseThrow();
        VersionSlots currentSlots = reader.read(card.getId(), current.getVersionNo());

        // 저장이 실패한 뒤 화면이 같은 글을 다시 보내도, 덮어쓸 수 없는 버전 위에 같은 내용의 새 버전이 생기지 않게 한다
        if (sameTexts(currentSlots, sent)) {
            return new SavedDraft(card.getPublicId(), current.getVersionNo(), sent, current.getUpdatedAt());
        }

        if (current.isOverwritable()) {
            return overwrite(card, current, sent);
        }

        CardVersion next = writer.startNext(card, VersionSource.USER_EDIT);
        fill(card.getId(), currentSlots, next.getVersionNo(), sent);
        themeSuggester.settle(card, reader.read(card.getId(), next.getVersionNo()));

        return new SavedDraft(card.getPublicId(), next.getVersionNo(), sent, next.getUpdatedAt());

    }

    private SavedDraft overwrite(Card card, CardVersion current, Map<StarField, String> sent) {

        // 덮어쓰는 직접 수정 버전은 늘 바로 앞 번호에서 갈라져 나오고, 직접 작성 카드의 1번 버전은 비교할 버전이 없다
        VersionSlots branchedFrom = current.getVersionNo() == 1
                ? null
                : reader.read(card.getId(), (short) (current.getVersionNo() - 1));

        statements.deleteAllOfVersion(card.getId(), current.getVersionNo());
        notes.deleteAllOfVersion(card.getId(), current.getVersionNo());
        fill(card.getId(), branchedFrom, current.getVersionNo(), sent);
        current.markSaved();
        card.markEdited();
        themeSuggester.settle(card, reader.read(card.getId(), current.getVersionNo()));

        return new SavedDraft(card.getPublicId(), current.getVersionNo(), sent, current.getUpdatedAt());

    }

    private void fill(Long cardId, VersionSlots branchedFrom, short versionNo, Map<StarField, String> sent) {

        for (StarField field : StarField.values()) {
            String text = sent.get(field);
            // 비운 칸을 그대로 비워 둔 것도 안 고친 칸이라, 문장은 없어도 비운 이유 메모가 따라온다
            if (branchedFrom != null && Objects.equals(text, branchedFrom.textOf(field))) {
                writer.copySlot(cardId, branchedFrom.versionNo(), field, versionNo);
            } else if (text != null) {
                statements.save(CardStatement.userStated(cardId, versionNo, field, text));
            }
        }

    }

    /**
     * 키가 없는 칸은 비우라는 뜻인지 그대로 두라는 뜻인지 알 수 없어 거절하고, 값이 null 인 칸은 비운 칸으로 본다
     * 공백뿐인 칸은 빈 칸(null)으로 바꾸고, 글이 있는 칸은 앞뒤 공백까지 그대로 둔다
     */
    private static Map<StarField, String> checkedTexts(Map<StarField, String> texts) {

        Map<StarField, String> checked = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            if (!texts.containsKey(field)) {
                throw new InvalidCardInputException(field + " 칸이 빠졌습니다. 비운 칸도 null 로 보내야 합니다.");
            }
            String text = texts.get(field);
            if (text != null && text.length() > SLOT_MAX_LENGTH) {
                throw new InvalidCardInputException("각 칸은 " + SLOT_MAX_LENGTH + "자를 넘을 수 없습니다.");
            }
            checked.put(field, text == null || CardText.isBlank(text) ? null : text);
        }

        return checked;

    }

    private static boolean sameTexts(VersionSlots slots, Map<StarField, String> sent) {

        for (StarField field : StarField.values()) {
            if (!Objects.equals(slots.textOf(field), sent.get(field))) {
                return false;
            }
        }

        return true;

    }
}
