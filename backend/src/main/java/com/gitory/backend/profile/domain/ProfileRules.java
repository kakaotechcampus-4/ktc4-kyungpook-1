package com.gitory.backend.profile.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 인적사항 입력을 검사해 저장할 값으로 바꾼다
 * 한 칸이라도 틀리면 어느 목록의 몇 번째인지 적어 400 으로 답하게 해, 화면이 그 칸을 짚을 수 있게 한다
 */
final class ProfileRules {

    static final int MAX_EDUCATIONS = 5;
    static final int MAX_CAREERS = 10;
    static final int MAX_LANGUAGES = 10;
    static final int MAX_CERTIFICATIONS = 20;
    static final int MAX_INTERESTS = 3;
    private static final int TEXT_MAX_LENGTH = 50;
    private static final int SCORE_MAX_LENGTH = 20;
    // 국내 대학이 쓰는 학점 만점과 100점 환산이다
    private static final Set<BigDecimal> GPA_MAXES = Set.of(new BigDecimal("4.0"), new BigDecimal("4.3"),
            new BigDecimal("4.5"), new BigDecimal("100"));

    private ProfileRules() {
    }

    static List<Education> educations(List<ProfileInput.EducationInput> inputs) {

        return checkedList(inputs, "학력", MAX_EDUCATIONS, (input, at) -> {
            String startMonth = optionalMonth(input.startMonth(), at + " 입학 달");
            String endMonth = optionalMonth(input.endMonth(), at + " 졸업(예정) 달");
            requireOrdered(startMonth, endMonth, at);
            requireGpa(input.gpa(), input.gpaMax(), at);
            return new Education(oneOf(EducationLevel.class, input.level(), at + " 학력 구분"),
                    optionalText(input.school(), at + " 학교 이름", TEXT_MAX_LENGTH),
                    optionalText(input.major(), at + " 전공", TEXT_MAX_LENGTH),
                    oneOf(EducationStatus.class, input.status(), at + " 상태"),
                    startMonth, endMonth, input.gpa(), input.gpaMax());
        });

    }

    static List<Career> careers(List<ProfileInput.CareerInput> inputs) {

        return checkedList(inputs, "경력", MAX_CAREERS, (input, at) -> {
            String startMonth = requiredMonth(input.startMonth(), at + " 입사 달");
            String endMonth = optionalMonth(input.endMonth(), at + " 퇴사 달");
            requireOrdered(startMonth, endMonth, at);
            return new Career(requiredText(input.company(), at + " 회사 이름", TEXT_MAX_LENGTH),
                    optionalText(input.role(), at + " 직무", TEXT_MAX_LENGTH),
                    oneOf(EmploymentType.class, input.type(), at + " 고용 형태"), startMonth, endMonth);
        });

    }

    static List<LanguageScore> languages(List<ProfileInput.LanguageInput> inputs) {

        return checkedList(inputs, "어학", MAX_LANGUAGES, (input, at) -> {
            LanguageTest test = oneOf(LanguageTest.class, input.test(), at + " 시험");
            // 목록에 있는 시험은 이름이 정해져 있어, 화면이 이름 칸을 남겨 보내도 저장하지 않는다
            String testName = test == LanguageTest.OTHER
                    ? requiredText(input.testName(), at + " 시험 이름", TEXT_MAX_LENGTH)
                    : null;
            return new LanguageScore(test, testName, requiredText(input.score(), at + " 점수·등급", SCORE_MAX_LENGTH),
                    optionalMonth(input.acquiredMonth(), at + " 취득 달"));
        });

    }

    static List<Certification> certifications(List<ProfileInput.CertificationInput> inputs) {

        return checkedList(inputs, "자격증", MAX_CERTIFICATIONS, (input, at) -> new Certification(
                requiredText(input.name(), at + " 이름", TEXT_MAX_LENGTH),
                optionalText(input.issuer(), at + " 발급 기관", TEXT_MAX_LENGTH),
                optionalMonth(input.acquiredMonth(), at + " 취득 달")));

    }

    private interface Check<I, O> {
        O apply(I input, String at);
    }

    /** 관심 분야는 안 보내면(null) 저장된 값을 그대로 두고, 보내면 통째로 바꾼다 — 지금 배포된 화면이 아직 이 칸을 보내지 않아서다 */
    static List<InterestCategory> interests(List<String> inputs) {

        if (inputs == null) {
            return null;
        }
        if (inputs.size() > MAX_INTERESTS) {
            throw new InvalidProfileInputException("관심 분야는 " + MAX_INTERESTS + "개까지 고를 수 있습니다.");
        }
        List<InterestCategory> checked = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            String at = "관심 분야 " + (index + 1) + "번째";
            InterestCategory category = oneOf(InterestCategory.class, inputs.get(index), at);
            if (checked.contains(category)) {
                throw new InvalidProfileInputException(at + "가 앞의 것과 겹칩니다.");
            }
            checked.add(category);
        }
        return List.copyOf(checked);

    }

    // 목록을 통째로 바꾸는 요청이라 빠진 목록을 빈 목록으로 보면 실수로 보낸 요청이 저장된 값을 지우므로, 네 목록 모두 보내야 한다
    private static <I, O> List<O> checkedList(List<I> inputs, String name, int max, Check<I, O> check) {

        if (inputs == null) {
            throw new InvalidProfileInputException(name + " 목록을 보내 주세요. 없으면 빈 목록([])입니다.");
        }
        if (inputs.size() > max) {
            throw new InvalidProfileInputException(name + "은 " + max + "개까지 넣을 수 있습니다.");
        }

        List<O> checked = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            String at = name + " " + (index + 1) + "번째";
            I input = inputs.get(index);
            if (input == null) {
                throw new InvalidProfileInputException(at + " 항목이 비었습니다.");
            }
            checked.add(check.apply(input, at));
        }
        return List.copyOf(checked);

    }

    private static void requireGpa(BigDecimal gpa, BigDecimal gpaMax, String at) {

        if (gpa == null && gpaMax == null) {
            return;
        }
        if (gpa == null || gpaMax == null) {
            throw new InvalidProfileInputException(at + " 학점과 만점은 함께 보내 주세요.");
        }
        if (GPA_MAXES.stream().noneMatch(allowed -> allowed.compareTo(gpaMax) == 0)) {
            throw new InvalidProfileInputException(at + " 학점 만점은 4.0·4.3·4.5·100 중 하나여야 합니다.");
        }
        if (gpa.signum() < 0 || gpa.compareTo(gpaMax) > 0) {
            throw new InvalidProfileInputException(at + " 학점은 0 부터 만점까지입니다.");
        }

    }

    private static void requireOrdered(String startMonth, String endMonth, String at) {

        if (startMonth != null && endMonth != null && YearMonth.parse(endMonth).isBefore(YearMonth.parse(startMonth))) {
            throw new InvalidProfileInputException(at + " 끝난 달이 시작한 달보다 앞설 수 없습니다.");
        }

    }

    private static <E extends Enum<E>> E oneOf(Class<E> type, String value, String field) {

        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InvalidProfileInputException(
                        field + "은 " + Arrays.toString(type.getEnumConstants()) + " 중 하나여야 합니다."));

    }

    private static String requiredText(String text, String field, int maxLength) {

        String checked = optionalText(text, field, maxLength);
        if (checked == null) {
            throw new InvalidProfileInputException(field + "을 입력해 주세요.");
        }
        return checked;

    }

    /** 앞뒤 공백은 지우고, 빈 글이면 없음(null)이다 */
    private static String optionalText(String text, String field, int maxLength) {

        if (text == null || isBlank(text)) {
            return null;
        }
        // PostgreSQL 은 글에 U+0000 을 저장하지 못해 500 이 되므로, 저장하기 전에 400 으로 거절한다
        if (text.indexOf('\u0000') >= 0) {
            throw new InvalidProfileInputException(field + "에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        String stripped = text.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidProfileInputException(field + "은 " + maxLength + "자를 넘을 수 없습니다.");
        }
        return stripped;

    }

    private static String requiredMonth(String month, String field) {

        String checked = optionalMonth(month, field);
        if (checked == null) {
            throw new InvalidProfileInputException(field + "을 2025-03 처럼 YYYY-MM 으로 보내 주세요.");
        }
        return checked;

    }

    private static String optionalMonth(String month, String field) {

        if (month == null || isBlank(month)) {
            return null;
        }
        try {
            return YearMonth.parse(month).toString();
        } catch (DateTimeParseException e) {
            throw new InvalidProfileInputException(field + "을 2025-03 처럼 YYYY-MM 으로 보내 주세요.");
        }

    }

    /** 화면은 JS trim 으로 빈 글을 가르므로 카드 글과 같은 기준(NBSP·U+FEFF 포함)으로 빈 글을 본다 */
    private static boolean isBlank(String text) {

        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);

    }
}
