package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class CandidateManualTest {

    private static Candidate manual() {

        return Candidate.manual(10L, "RECALL", "/main/java/ 작업", "  로그인을 맡았다  ", CandidateMeta.of(List.of()));

    }

    @Test
    @DisplayName("직접 추가 후보는 MANUAL 종류의 NEW 후보이고 받은 연결·ref·제목·이유를 그대로 담는다")
    void keepsGivenValues() {

        Candidate candidate = manual();

        assertThat(candidate.getSourceType()).isEqualTo(CandidateType.MANUAL);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
        assertThat(candidate.getUserRepositoryId()).isEqualTo(10L);
        assertThat(candidate.getSourceRef()).isEqualTo("RECALL");
        assertThat(candidate.getTitle()).isEqualTo("/main/java/ 작업");
        assertThat(candidate.getReasonText()).isEqualTo("  로그인을 맡았다  ");
        assertThat(candidate.getPublicId()).isNotNull();

    }

    @Test
    @DisplayName("대표 PR 번호와 점수와 묶음 숫자는 비어 있고 PR·이슈 번호 목록은 빈 목록이며 카드감 낮음이 아니다")
    void leavesGroupingValuesEmpty() {

        Candidate candidate = manual();

        assertThat(candidate.getGithubPrNumber()).isNull();
        assertThat(candidate.getScore()).isNull();
        assertThat(candidate.getPullRequestNumbers()).isEmpty();
        assertThat(candidate.getIssueNumbers()).isEmpty();
        assertThat(candidate.isLowCardWorth()).isFalse();
        assertThat(candidate.getFileCount()).isZero();
        assertThat(candidate.getReviewCommentCount()).isZero();
        assertThat(candidate.getSpanDays()).isNull();
        assertThat(candidate.getCodeRatio()).isNull();

    }

    @Test
    @DisplayName("받은 파일 수·기간·코드 비율을 그대로 담고 리뷰 댓글 수는 0 이다")
    void keepsGivenMeta() {

        Candidate candidate = Candidate.manual(10L, "MANUAL", "직접 추가", "내용",
                new CandidateMeta(3, 5, new BigDecimal("0.50")));

        assertThat(candidate.getFileCount()).isEqualTo(3);
        assertThat(candidate.getSpanDays()).isEqualTo(5);
        assertThat(candidate.getCodeRatio()).isEqualByComparingTo("0.50");
        assertThat(candidate.getReviewCommentCount()).isZero();

    }

    @Test
    @DisplayName("처음부터 손댄 표시가 켜져 있어 손댄 후보다")
    void startsAsTouched() {

        Candidate candidate = manual();

        assertThat(candidate.isUserModified()).isTrue();
        assertThat(candidate.isTouched()).isTrue();

    }
}
