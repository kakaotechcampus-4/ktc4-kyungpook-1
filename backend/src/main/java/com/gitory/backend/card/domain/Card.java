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

    // 레포 밖 경험을 묶는 활동으로, 저장소와 함께 값이 있는 카드는 없다(DB 제약)
    private Long activityId;

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

    private Long candidateId;

    // 후보로 만든 카드의 초안 Job 이고, Job 이 끝나도 지우지 않아 Job 이 자기 카드를 찾을 수 있다
    private UUID generationJobId;
    private boolean generationPartial;
    // 초안 Job 이 이 카드를 채우지 못한 이유로, Job 이 끝나도 남아 화면이 다시 시도를 보여 준다
    @Enumerated(EnumType.STRING)
    private CardDraftFailure generationFailure;

    // 질문 API 는 끝이면 null 이라 AI 가 그만 묻기로 한 이유를 카드에 남기고, 새 질문이 생기면 지운다
    private String interviewEndReason;

    // enum 순번이 아니라 이름으로 저장해, 목록 가운데에 값을 더해도 저장된 태그의 뜻이 바뀌지 않게 한다
    // Hibernate 가 INSERT 에 이 칸을 넣어 DB 기본값이 쓰이지 않으므로 빈 목록으로 시작한다
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> areas;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> techs;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;

    private Card(Long userId, Long userRepositoryId, CardKind kind, CardOrigin origin, String title, String period,
                 String idempotencyKey, Long candidateId, UUID generationJobId) {
        Instant now = CardTime.now();
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.userRepositoryId = userRepositoryId;
        this.cardType = kind;
        this.origin = origin;
        this.title = checkedTitle(title);
        this.period = checkedPeriod(period);
        this.status = CardStatus.DRAFT;
        this.currentVersion = 1;
        this.maskRules = List.of();
        this.idempotencyKey = idempotencyKey;
        this.candidateId = candidateId;
        this.generationJobId = generationJobId;
        this.generationPartial = false;
        this.areas = List.of();
        this.techs = List.of();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 사용자가 직접 쓰는 정성 카드를 1번 버전이 현재 버전인 작성 중 상태로 만들고, 1번 버전 행은 {@link CardVersionWriter} 가 만든다 */
    public static Card startManual(Long userId, Long userRepositoryId, Long activityId, String title, String period,
                                   String idempotencyKey) {

        Card card = new Card(userId, userRepositoryId, CardKind.QUALITATIVE, CardOrigin.MANUAL, title, period,
                idempotencyKey, null, null);
        card.activityId = activityId;

        return card;

    }

    /** 후보로 만든 카드를 기간 없이 작성 중 상태로 만들고, 1번 버전 행은 {@link CardVersionWriter} 가 만든다 */
    public static Card startFromCandidate(Long userId, Long userRepositoryId, Long candidateId, String title,
                                          CardKind kind, UUID generationJobId) {

        return new Card(userId, userRepositoryId, kind, CardOrigin.AI, title, null, null, candidateId,
                generationJobId);

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

        this.title = checkedTitle(title);
        changed();

    }

    /** 빈 글이나 공백뿐인 글을 받으면 기간을 지운다 */
    public void changePeriod(String period) {

        this.period = checkedPeriod(period);
        changed();

    }

    /** 받은 순서 그대로 저장하고, 저장된 규칙과 순서까지 같으면 바꾸지 않고 false 를 돌려준다 */
    public boolean replaceMaskRules(List<MaskRule> rules) {

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

    /**
     * 칸 글이 바뀐 뒤 지금 버전 글로 상태를 다시 정하고, 화면이 자동 저장하므로 확정본은 남기지 않는다
     * 작성 중은 네 칸이 확인 필요 없이 모두 차야 확정하고, 확정은 상황·행동이 빌 때만 작성 중으로 돌린다
     */
    public void settle(VersionSlots slots) {

        if (!isConfirmed() && slots.isComplete()) {
            this.status = CardStatus.CONFIRMED;
            this.confirmedAt = CardTime.now();
            changed();
        } else if (isConfirmed() && !slots.isConfirmable()) {
            reopen();
        }

    }

    /** 초안 Job 이 시간이 모자라 일부 칸을 채우지 못했을 때 부르며, 화면은 이 카드에 채운 칸까지만 보여 준다 */
    void markGenerationPartial() {

        this.generationPartial = true;
        changed();

    }

    /** 초안 Job 이 AI 초안을 채우지 못했을 때 부르며, 다시 시도하면 지운다 */
    void markGenerationFailed(CardDraftFailure failure) {

        this.generationFailure = failure;
        changed();

    }

    /** 서버가 다시 켜져 같은 Job 이 실패했던 카드를 다시 채우면, 채운 카드에 실패 안내가 남지 않게 지운다 */
    void clearGenerationFailure() {

        this.generationFailure = null;

    }

    /** 초안 Job 은 1번 버전에만 담을 수 있어, 초안 실패가 남아 있고 되묻기·확정으로 버전이 올라가지 않은 후보 카드만 다시 채운다 */
    public void requireDraftRetryable() {

        requireDraft();
        if (generationFailure == null || currentVersion != 1 || candidateId == null) {
            throw new CardDraftNotRetryableException();
        }

    }

    /** 초안을 채우지 못한 카드를 새 초안 Job 으로 다시 채우며, 새 Job 이 이 카드를 찾을 수 있게 Job 을 바꾼다 */
    void restartGeneration(UUID jobId) {

        requireDraftRetryable();
        this.generationJobId = jobId;
        this.generationFailure = null;
        this.generationPartial = false;
        changed();

    }

    /** 초안 Job 이 1번 버전을 쓸 때 한 번만 부르며, 태그는 카드 내용이 아니라 목록을 거르는 표시라 updatedAt 을 바꾸지 않는다 */
    void tag(List<CardArea> areas, List<CardTech> techs) {

        this.areas = areas.stream().map(Enum::name).toList();
        this.techs = techs.stream().map(Enum::name).toList();

    }

    public List<CardArea> areaTags() {

        return areas.stream().map(CardArea::valueOf).toList();

    }

    public List<CardTech> techTags() {

        return techs.stream().map(CardTech::valueOf).toList();

    }

    /** 되묻기를 끝낸 이유는 카드 내용이 아니라 화면 안내라 updatedAt 을 바꾸지 않는다 */
    public void endInterview(String reason) {

        this.interviewEndReason = reason;

    }

    public void resumeInterview() {

        this.interviewEndReason = null;

    }

    /** 임시 저장이 현재 버전을 덮어써 카드 행의 다른 칸이 그대로일 때 부른다 */
    public void markEdited() {

        changed();

    }

    void switchTo(short versionNo) {

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
