package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 사용자 카드 한 장으로, 확정된 카드는 다시 열기 전에는 제목·기간·가리기 규칙·버전을 바꿀 수 없다
 * 목록이 최근에 고친 순으로 나오도록 내용을 바꾸는 메서드는 모두 updatedAt 을 갱신한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card")
public class Card {

    private static final int TITLE_MAX_LENGTH = 256;
    private static final int PERIOD_MAX_LENGTH = 50;
    private static final int MASK_RULE_MAX_COUNT = 20;
    private static final int MASK_TEXT_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // URL 에는 내부 id 대신 이 값(UUID)만 내보낸다
    private UUID publicId;

    private Long userId;
    private Long userRepositoryId;

    @Enumerated(EnumType.STRING)
    private CardKind cardType;

    @Enumerated(EnumType.STRING)
    private CardOrigin origin;

    private String title;
    private String period;

    @Enumerated(EnumType.STRING)
    private CardStatus status;

    private short currentVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaskRule> maskRules;

    private String idempotencyKey;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;

    private Card(Long userId, Long userRepositoryId, String title, String period, String idempotencyKey) {
        Instant now = CardTime.now();
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.cardType = CardKind.QUALITATIVE;
        this.origin = CardOrigin.MANUAL;
        this.title = checkedTitle(title);
        this.period = checkedPeriod(period);
        this.status = CardStatus.DRAFT;
        this.currentVersion = 1;
        this.maskRules = List.of();
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 사용자가 직접 쓰는 정성 카드를 1번 버전이 현재 버전인 작성 중 상태로 만들고, 1번 버전 행은 {@link CardVersionWriter} 가 만든다 */
    public static Card startManual(Long userId, Long userRepositoryId, String title, String period,
                                   String idempotencyKey) {

        return new Card(userId, userRepositoryId, title, period, idempotencyKey);

    }

    public boolean isConfirmed() {

        return status == CardStatus.CONFIRMED;

    }

    public void requireDraft() {

        if (isConfirmed()) {
            throw new CardConfirmedException();
        }

    }

    public void rename(String title) {

        requireDraft();
        this.title = checkedTitle(title);
        changed();

    }

    /** 빈 글이나 공백뿐인 글을 받으면 기간을 지운다 */
    public void changePeriod(String period) {

        requireDraft();
        this.period = checkedPeriod(period);
        changed();

    }

    /** 받은 순서 그대로 저장하고, 저장된 규칙과 순서까지 같으면 바꾸지 않고 false 를 돌려준다 */
    public boolean replaceMaskRules(List<MaskRule> rules) {

        requireDraft();
        List<MaskRule> checked = checkedMaskRules(rules);

        if (checked.equals(maskRules)) {
            return false;
        }

        this.maskRules = checked;
        changed();

        return true;

    }

    public void confirm() {

        requireDraft();
        this.status = CardStatus.CONFIRMED;
        this.confirmedAt = CardTime.now();
        changed();

    }

    /** 작성 중인 카드를 다시 열면 아무것도 바꾸지 않는다 */
    public void reopen() {

        if (!isConfirmed()) {
            return;
        }

        this.status = CardStatus.DRAFT;
        this.confirmedAt = null;
        changed();

    }

    /** 임시 저장이 현재 버전을 덮어써 카드 행의 다른 칸이 그대로일 때 부른다 */
    public void markEdited() {

        requireDraft();
        changed();

    }

    // 확정된 카드에 새 버전이 붙으면 확정본이 현재 버전이 아니게 되므로 막는다
    void switchTo(short versionNo) {

        requireDraft();
        this.currentVersion = versionNo;
        changed();

    }

    private void changed() {

        this.updatedAt = CardTime.now();

    }

    private static String checkedTitle(String title) {

        if (title == null || CardText.isBlank(title)) {
            throw new InvalidCardInputException("제목을 입력해 주세요.");
        }

        if (title.length() > TITLE_MAX_LENGTH) {
            throw new InvalidCardInputException("제목은 " + TITLE_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        CardText.requireNoNul(title, "제목");

        return title;

    }

    private static String checkedPeriod(String period) {

        if (period == null || CardText.isBlank(period)) {
            return null;
        }

        if (period.length() > PERIOD_MAX_LENGTH) {
            throw new InvalidCardInputException("기간은 " + PERIOD_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        CardText.requireNoNul(period, "기간");

        return period;

    }

    private static List<MaskRule> checkedMaskRules(List<MaskRule> rules) {

        if (rules == null) {
            throw new InvalidCardInputException("가리기 규칙 목록이 필요합니다.");
        }

        if (rules.size() > MASK_RULE_MAX_COUNT) {
            throw new InvalidCardInputException("가리기 규칙은 " + MASK_RULE_MAX_COUNT + "개까지 저장할 수 있습니다.");
        }

        for (MaskRule rule : rules) {
            if (rule == null || rule.from() == null || rule.to() == null) {
                throw new InvalidCardInputException("가리기 규칙에는 가릴 말과 바꿀 말이 모두 있어야 합니다.");
            }
            if (CardText.isBlank(rule.from())) {
                throw new InvalidCardInputException("가릴 말을 입력해 주세요.");
            }
            if (rule.from().length() > MASK_TEXT_MAX_LENGTH || rule.to().length() > MASK_TEXT_MAX_LENGTH) {
                throw new InvalidCardInputException("가리기 규칙의 글은 " + MASK_TEXT_MAX_LENGTH + "자를 넘을 수 없습니다.");
            }
            CardText.requireNoNul(rule.from(), "가릴 말");
            CardText.requireNoNul(rule.to(), "바꿀 말");
        }

        return List.copyOf(rules);

    }
}
