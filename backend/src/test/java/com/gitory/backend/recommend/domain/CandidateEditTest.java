package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

class CandidateEditTest {

    private static Candidate grouped() {

        return Candidate.grouped(10L, CandidateType.COMMIT_CLUSTER, null, "2026-09-23", List.of(), List.of(), "결제 모듈 정리",
                "같은 날 이어진 작업", new BigDecimal("0.3000"), false, 4, 0, 2, new BigDecimal("0.5000"));

    }

    private static Candidate used() {

        Candidate candidate = grouped();
        ReflectionTestUtils.setField(candidate, "status", CandidateStatus.USED);

        return candidate;

    }

    @Test
    @DisplayName("새 후보를 제외하면 EXCLUDED 가 되고 복원하면 NEW 로 돌아온다")
    void excludesAndRestores() {

        Candidate candidate = grouped();

        candidate.exclude();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.EXCLUDED);

        candidate.restore();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);

    }

    @Test
    @DisplayName("이미 그 상태인 후보를 다시 제외하거나 복원해도 그대로 둔다")
    void keepsStatusWhenAlreadyThere() {

        Candidate candidate = grouped();

        candidate.restore();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);

        candidate.exclude();
        candidate.exclude();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.EXCLUDED);

    }

    @Test
    @DisplayName("카드로 쓴 후보는 제외도 복원도 확인도 받지 않고 상태를 그대로 둔다")
    void rejectsEveryEditOfUsedCandidate() {

        Candidate candidate = used();

        assertThatThrownBy(candidate::exclude).isInstanceOf(CandidateNotEditableException.class);
        assertThatThrownBy(candidate::restore).isInstanceOf(CandidateNotEditableException.class);
        assertThatThrownBy(candidate::ensureEditable).isInstanceOf(CandidateNotEditableException.class);
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.USED);

    }

    @Test
    @DisplayName("NEW 와 EXCLUDED 후보는 수정할 수 있다")
    void allowsEditOfNewAndExcludedCandidates() {

        Candidate candidate = grouped();
        candidate.ensureEditable();

        candidate.exclude();
        candidate.ensureEditable();

    }

    @Test
    @DisplayName("제외하고 복원하는 것만으로는 손댄 표시를 켜지 않는다")
    void doesNotMarkUserModifiedByItself() {

        Candidate candidate = grouped();

        candidate.exclude();
        candidate.restore();

        assertThat(candidate.isUserModified()).isFalse();

    }

    @Test
    @DisplayName("제외하고 복원해도 다른 칸은 바뀌지 않는다")
    void changesNothingButStatus() {

        Candidate candidate = grouped();

        candidate.exclude();
        candidate.restore();

        assertThat(candidate.getTitle()).isEqualTo("결제 모듈 정리");
        assertThat(candidate.getReasonText()).isEqualTo("같은 날 이어진 작업");
        assertThat(candidate.getScore()).isEqualByComparingTo("0.3000");
        assertThat(candidate.getFileCount()).isEqualTo(4);
        assertThat(candidate.getSpanDays()).isEqualTo(2);
        assertThat(candidate.getCodeRatio()).isEqualByComparingTo("0.5000");
        assertThat(candidate.getSourceType()).isEqualTo(CandidateType.COMMIT_CLUSTER);

    }

    @Test
    @DisplayName("사용자가 붙인 커밋은 USER 출처이고 AI 가 넣은 커밋은 AI 출처이며 둘 다 포함된 상태로 시작한다")
    void commitsStartIncludedWithTheirOrigin() {

        CandidateCommit attached = CandidateCommit.attached(1L, 2L, "src/App.java");
        CandidateCommit grouped = CandidateCommit.grouped(1L, 3L, "");

        assertThat(attached.getOrigin()).isEqualTo(CandidateCommitOrigin.USER);
        assertThat(grouped.getOrigin()).isEqualTo(CandidateCommitOrigin.AI);
        assertThat(attached.isIncluded()).isTrue();
        assertThat(grouped.isIncluded()).isTrue();
        assertThat(attached.getCandidateId()).isEqualTo(1L);
        assertThat(attached.getCommitId()).isEqualTo(2L);
        assertThat(attached.getDisplayPath()).isEqualTo("src/App.java");

    }

    @Test
    @DisplayName("커밋을 빼면 포함 여부만 꺼지고 다시 넣으면 켜진다")
    void excludesAndIncludesCommit() {

        CandidateCommit commit = CandidateCommit.grouped(1L, 2L, "src/App.java");

        commit.exclude();
        assertThat(commit.isIncluded()).isFalse();
        assertThat(commit.getOrigin()).isEqualTo(CandidateCommitOrigin.AI);
        assertThat(commit.getDisplayPath()).isEqualTo("src/App.java");

        commit.include();
        assertThat(commit.isIncluded()).isTrue();

    }
}
