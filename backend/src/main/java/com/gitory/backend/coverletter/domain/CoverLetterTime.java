package com.gitory.backend.coverletter.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 시각을 DB 가 저장하는 µs 까지만 만들어, 저장 직후 엔티티로 만든 응답과 나중에 다시 읽은 값이 같게 한다 */
final class CoverLetterTime {

    private CoverLetterTime() {
    }

    static Instant now() {

        return Instant.now().truncatedTo(ChronoUnit.MICROS);

    }
}
