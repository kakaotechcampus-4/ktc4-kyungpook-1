package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class CandidateTest {

    private static Candidate grouped() {

        return Candidate.grouped(10L, CandidateType.PR, 42, "#42", List.of(42), List.of(), "로그인 세션 처리",
                "리뷰 코멘트 14건이 남은 작업", new BigDecimal("0.6120"), false, 12, 14, 21, new BigDecimal("0.9200"));

    }

    @Test
    @DisplayName("AI 경험 그룹화로 만든 후보는 사용자가 손대지 않은 NEW 후보로 만들어진다")
    void groupedCandidateStartsNewAndUntouched() {

        Candidate candidate = grouped();

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
        assertThat(candidate.isUserModified()).isFalse();
        assertThat(candidate.isTouched()).isFalse();
        assertThat(candidate.getPublicId()).isNotNull();

    }

    @Test
    @DisplayName("손댄 표시는 한 번 켜면 다시 불러도 꺼지지 않고 그 후보는 손댄 후보가 된다")
    void userModifiedStaysOn() {

        Candidate candidate = grouped();

        candidate.markUserModified();
        candidate.markUserModified();

        assertThat(candidate.isUserModified()).isTrue();
        assertThat(candidate.isTouched()).isTrue();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);

    }

    @Test
    @DisplayName("새 후보를 카드로 쓰면 USED 가 되고, 새 후보가 아니면 상태를 바꾸지 않고 거절한다")
    void useMovesNewCandidateToUsedOnly() {

        Candidate candidate = grouped();

        candidate.use();

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.USED);
        assertThat(candidate.isTouched()).isTrue();
        assertThatThrownBy(candidate::use).isInstanceOf(IllegalStateException.class);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.USED);

    }

    @Test
    @DisplayName("카드로 쓴 후보를 되돌리면 NEW 가 되어 다시 카드로 쓸 수 있다")
    void releaseMovesUsedCandidateBackToNew() {

        Candidate candidate = grouped();
        candidate.use();

        candidate.release();

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
        candidate.use();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.USED);

    }

    @Test
    @DisplayName("카드로 쓰지 않은 후보는 되돌려도 상태가 그대로다")
    void releaseLeavesNewAndExcludedCandidateAsIs() {

        Candidate fresh = grouped();
        Candidate excluded = grouped();
        excluded.exclude();

        fresh.release();
        excluded.release();

        assertThat(fresh.getStatus()).isEqualTo(CandidateStatus.NEW);
        assertThat(excluded.getStatus()).isEqualTo(CandidateStatus.EXCLUDED);

    }
}
