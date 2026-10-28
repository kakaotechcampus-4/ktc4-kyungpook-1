package com.gitory.backend.card.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

/**
 * 레포 밖 경험(동아리·대외활동 등)을 묶는 활동으로, 안의 카드는 모두 이 활동의 종류를 따른다
 * 기간은 달 단위라 그달 1일로 저장하고, 끝난 달이 없으면 진행 중이다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "activity")
public class Activity {

    private static final int NAME_MAX_LENGTH = 50;
    private static final int ROLE_MAX_LENGTH = 50;
    private static final String MONTH_FORMAT = "기간은 2025-03 처럼 YYYY-MM 으로 보내 주세요.";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long userId;
    private String name;

    @Enumerated(EnumType.STRING)
    private ActivityType activityType;

    private LocalDate startMonth;
    private LocalDate endMonth;
    private String role;
    private String idempotencyKey;
    private Instant createdAt;
    private Instant updatedAt;

    /** 끝난 달·역할은 null 이나 빈 글이면 없음(진행 중·역할 없음)이다 */
    public static Activity start(Long userId, String name, String type, String startMonth, String endMonth,
                                 String role, String idempotencyKey) {

        Activity activity = new Activity();
        Instant now = CardTime.now();
        activity.publicId = UUID.randomUUID();
        activity.userId = userId;
        activity.name = checkedName(name);
        activity.activityType = checkedType(type);
        activity.startMonth = checkedMonth(startMonth);
        activity.endMonth = isEmpty(endMonth) ? null : checkedMonth(endMonth);
        activity.role = checkedRole(role);
        activity.idempotencyKey = idempotencyKey;
        activity.createdAt = now;
        activity.updatedAt = now;
        activity.requireOrderedPeriod();

        return activity;

    }

    /**
     * null 인 값은 그대로 두고, 끝난 달·역할은 빈 글이면 지운다(카드 제목·기간 수정과 같은 규칙)
     * 모두 null 이면 바꿀 것이 없어 400 으로 답하게 한다
     */
    public void edit(String name, String type, String startMonth, String endMonth, String role) {

        if (name == null && type == null && startMonth == null && endMonth == null && role == null) {
            throw new InvalidCardInputException("바꿀 이름·종류·기간·역할을 보내 주세요.");
        }

        if (name != null) {
            this.name = checkedName(name);
        }
        if (type != null) {
            this.activityType = checkedType(type);
        }
        if (startMonth != null) {
            this.startMonth = checkedMonth(startMonth);
        }
        if (endMonth != null) {
            this.endMonth = isEmpty(endMonth) ? null : checkedMonth(endMonth);
        }
        if (role != null) {
            this.role = checkedRole(role);
        }
        requireOrderedPeriod();
        this.updatedAt = CardTime.now();

    }

    private void requireOrderedPeriod() {

        if (endMonth != null && endMonth.isBefore(startMonth)) {
            throw new InvalidCardInputException("끝난 달이 시작한 달보다 앞설 수 없습니다.");
        }

    }

    private static String checkedName(String name) {

        if (name == null || CardText.isBlank(name)) {
            throw new InvalidCardInputException("활동 이름을 입력해 주세요.");
        }

        if (name.length() > NAME_MAX_LENGTH) {
            throw new InvalidCardInputException("활동 이름은 " + NAME_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        CardText.requireNoNul(name, "활동 이름");

        return name;

    }

    private static ActivityType checkedType(String type) {

        return Arrays.stream(ActivityType.values())
                .filter(value -> value.name().equals(type))
                .findFirst()
                .orElseThrow(() -> new InvalidCardInputException(
                        "활동 종류는 " + Arrays.toString(ActivityType.values()) + " 중 하나여야 합니다."));

    }

    private static LocalDate checkedMonth(String month) {

        if (month == null) {
            throw new InvalidCardInputException(MONTH_FORMAT);
        }

        try {
            return YearMonth.parse(month).atDay(1);
        } catch (DateTimeParseException e) {
            throw new InvalidCardInputException(MONTH_FORMAT);
        }

    }

    private static String checkedRole(String role) {

        if (isEmpty(role)) {
            return null;
        }

        if (role.length() > ROLE_MAX_LENGTH) {
            throw new InvalidCardInputException("역할은 " + ROLE_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        CardText.requireNoNul(role, "역할");

        return role;

    }

    private static boolean isEmpty(String text) {

        return text == null || CardText.isBlank(text);

    }
}
