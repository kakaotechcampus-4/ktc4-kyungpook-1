package com.gitory.backend.profile.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 프로필 입력을 검사해 저장할 값으로 바꾼다
 * 한 칸이라도 틀리면 몇 번째인지 적어 400 으로 답하게 해, 화면이 그 칸을 짚을 수 있게 한다
 */
final class ProfileRules {

    static final int MAX_INTERESTS = 3;
    private static final int EMAIL_MAX_LENGTH = 254;

    private ProfileRules() {
    }

    /** 관심 분야는 안 보내면(null) 저장된 값을 그대로 두고, 보내면 통째로 바꾼다 */
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

    /** 안 보내면(null) 저장된 주소를 그대로 두고, 빈 글은 주소를 지운다는 뜻으로 빈 글을 돌려준다 */
    static String email(String input) {

        if (input == null) {
            return null;
        }
        if (input.indexOf('\u0000') >= 0) {
            throw new InvalidProfileInputException("메일 주소에 쓸 수 없는 글자(U+0000)가 있습니다.");
        }
        if (isBlank(input)) {
            return "";
        }
        String stripped = input.strip();
        if (stripped.length() > EMAIL_MAX_LENGTH) {
            throw new InvalidProfileInputException("메일 주소는 " + EMAIL_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }
        int at = stripped.indexOf('@');
        boolean oneAt = at > 0 && at == stripped.lastIndexOf('@');
        boolean hasSpace = stripped.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
        if (!oneAt || hasSpace || stripped.indexOf('.', at) < 0) {
            throw new InvalidProfileInputException("메일 주소 모양이 맞지 않습니다.");
        }
        return stripped;

    }

    private static <E extends Enum<E>> E oneOf(Class<E> type, String value, String field) {

        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new InvalidProfileInputException(
                        field + "은 " + Arrays.toString(type.getEnumConstants()) + " 중 하나여야 합니다."));

    }

    /** 화면은 JS trim 으로 빈 글을 가르므로 카드 글과 같은 기준(NBSP·U+FEFF 포함)으로 빈 글을 본다 */
    private static boolean isBlank(String text) {

        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF);

    }
}
