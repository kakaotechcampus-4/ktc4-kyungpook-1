package com.gitory.backend.coverletter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverLetterLiveLogTest {

    @Test
    @DisplayName("다시 집힌 단계가 진행을 새로 시작했으면, 늦게 끝난 앞 단계는 새 진행을 지우지 않는다")
    void staleRunDoesNotDropTheNewProgress() {

        CoverLetterLiveLog log = new CoverLetterLiveLog();
        CoverLetterLive stale = log.start(7L);
        CoverLetterLive current = log.start(7L);
        current.write(1, 0, "새 글");

        log.finish(7L, stale);

        assertThat(log.of(7L)).get().extracting(live -> live.writing().get(1))
                .isEqualTo(new CoverLetterView.Writing("새 글", 0));

    }
}
