package com.gitory.backend.card.domain;

/** 직접 작성 카드와 활동 추가가 같은 요청 키 규칙을 쓰게 한다 */
final class IdempotencyKey {

    private static final int MAX_LENGTH = 255;

    private IdempotencyKey() {
    }

    static String checked(String idempotencyKey) {

        // 빈 키를 키로 쓰면 빈 키로 온 요청이 모두 처음 만든 것 하나를 돌려받는다
        if (idempotencyKey == null || CardText.isBlank(idempotencyKey)) {
            return null;
        }

        // 아주 긴 키는 유니크 인덱스 한도에 걸려 저장 때 500 이 된다
        if (idempotencyKey.length() > MAX_LENGTH) {
            throw new InvalidCardInputException("Idempotency-Key 는 " + MAX_LENGTH + "자를 넘을 수 없습니다.");
        }

        return idempotencyKey;

    }
}
