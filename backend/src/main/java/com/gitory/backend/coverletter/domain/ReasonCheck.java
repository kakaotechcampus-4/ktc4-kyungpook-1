package com.gitory.backend.coverletter.domain;

import java.util.regex.Pattern;

/**
 * 자소서를 만들 때 받은 지원 이유가 글로 쓸 만한 모양인지 코드로만 가린다
 * 화면(lib/reasonCheck.ts)과 같은 규칙·같은 순서라, 여기만 바꾸면 화면에서 통과한 글이 400 으로 돌아온다
 */
final class ReasonCheck {

    private static final int MAX_LENGTH = 1_000;
    private static final int MIN_LETTERS = 20;
    private static final int MAX_REPEAT = 5;

    private static final Pattern HANGUL_SYLLABLE = Pattern.compile("[가-힣]");
    // 'ㅎㅇㅎㅇ' 같은 2~3자 조각도 막고, '10000000원' 같은 금액·연도는 두려고 숫자는 세지 않는다
    private static final Pattern REPEATED = Pattern.compile(
            "([^\\s\\d])\\1{" + (MAX_REPEAT - 1) + ",}|([^\\s\\d]{2,3})\\2{" + (MAX_REPEAT - 1) + ",}");
    // 'HTTP 통신' 처럼 글 속 낱말이나 'naver.com' 같은 이름은 주소로 보지 않는다
    private static final Pattern URL = Pattern.compile("(?i)https?://|www\\.");
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\S+");
    // U+0000 도 여기서 걸러진다
    private static final Pattern ALLOWED = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9\\s.,!?·'\"()\\-:;/%&~…\\[\\]+#「」`]*");

    private ReasonCheck() {
    }

    /** 규칙을 지키면 null 이고, 아니면 처음 걸린 규칙을 사용자에게 보일 한 문장으로 돌려준다 */
    static String problem(String memo) {

        if (memo == null) {
            return "지원 이유를 " + MIN_LETTERS + "자 이상 적어 주세요.";
        }
        if (memo.length() > MAX_LENGTH) {
            return "지원 이유는 " + MAX_LENGTH + "자를 넘을 수 없습니다.";
        }
        if (memo.codePoints().filter(c -> !Character.isWhitespace(c)).count() < MIN_LETTERS) {
            return "지원 이유를 " + MIN_LETTERS + "자 이상 적어 주세요.";
        }
        if (!HANGUL_SYLLABLE.matcher(memo).find()) {
            return "지원 이유는 한국어로 적어 주세요.";
        }
        if (REPEATED.matcher(memo).find()) {
            return "지원 이유에 같은 글자를 " + MAX_REPEAT + "번 이상 이어 쓸 수 없습니다.";
        }
        if (URL.matcher(memo).find()) {
            return "지원 이유에는 URL 을 넣을 수 없습니다.";
        }
        if (EMAIL.matcher(memo).find()) {
            return "지원 이유에는 이메일 주소를 넣을 수 없습니다.";
        }
        if (!ALLOWED.matcher(memo).matches()) {
            return "지원 이유에는 한글·영문·숫자·흔한 문장부호만 쓸 수 있습니다.";
        }
        return null;

    }
}
