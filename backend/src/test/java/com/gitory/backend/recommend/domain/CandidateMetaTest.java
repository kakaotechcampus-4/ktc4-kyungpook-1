package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gitory.backend.ingest.domain.ChangedFile;
import com.gitory.backend.ingest.domain.StoredCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

class CandidateMetaTest {

    private static final Instant MONDAY = Instant.parse("2026-09-21T01:00:00Z");

    private static StoredCommit commit(Instant authoredAt, Integer changedFiles, ChangedFile... files) {

        return new StoredCommit(1L, "a".repeat(40), authoredAt, changedFiles, List.of(files));

    }

    private static StoredCommit commit(ChangedFile... files) {

        return commit(MONDAY, files.length, files);

    }

    private static ChangedFile file(String path, int additions, int deletions) {

        return new ChangedFile(path, additions, deletions);

    }

    @Test
    @DisplayName("파일 수는 후보 커밋들이 바꾼 서로 다른 경로 수다")
    void fileCountIsDistinctPaths() {

        CandidateMeta meta = CandidateMeta.of(List.of(
                commit(file("src/Session.java", 3, 1), file("README.md", 1, 0)),
                commit(file("src/Session.java", 2, 2), file("src/Token.java", 5, 0))));

        assertThat(meta.fileCount()).isEqualTo(3);

    }

    @Test
    @DisplayName("바뀐 파일을 아직 채우지 않은 커밋뿐이면 파일 수는 커밋마다 GitHub 이 준 바뀐 파일 수의 합이다")
    void fileCountFallsBackToChangedFiles() {

        CandidateMeta meta = CandidateMeta.of(List.of(commit(MONDAY, 4), commit(MONDAY, null), commit(MONDAY, 6)));

        assertThat(meta.fileCount()).isEqualTo(10);

    }

    @Test
    @DisplayName("경로를 아는 커밋이 하나라도 있으면 바뀐 파일 수의 합이 아니라 경로 수를 쓴다")
    void fileCountPrefersKnownPaths() {

        CandidateMeta meta = CandidateMeta.of(List.of(commit(MONDAY, 7), commit(file("src/Session.java", 1, 1))));

        assertThat(meta.fileCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("기간은 가장 이른 커밋과 가장 늦은 커밋 사이를 24시간 단위로 내린 날 수이고, 몇 시간 안의 커밋이면 0 이다")
    void spanDaysIsWholeDaysBetweenFirstAndLast() {

        CandidateMeta threeWeeks = CandidateMeta.of(List.of(
                commit(MONDAY.plus(Duration.ofHours(3)), 1),
                commit(MONDAY.plus(Duration.ofDays(21)).minus(Duration.ofMinutes(1)), 1),
                commit(MONDAY, 1)));
        CandidateMeta sameAfternoon = CandidateMeta.of(List.of(
                commit(MONDAY, 1),
                commit(MONDAY.plus(Duration.ofHours(4)), 1)));

        assertThat(threeWeeks.spanDays()).isEqualTo(20);
        assertThat(sameAfternoon.spanDays()).isZero();

    }

    @Test
    @DisplayName("작성 시각을 아는 커밋이 없으면 기간은 비우고, 하나만 알면 0 이다")
    void spanDaysNeedsAuthoredTime() {

        assertThat(CandidateMeta.of(List.of(commit(null, 1), commit(null, 2))).spanDays()).isNull();
        assertThat(CandidateMeta.of(List.of(commit(null, 1), commit(MONDAY, 2))).spanDays()).isZero();

    }

    @Test
    @DisplayName("코드 비율은 바뀐 줄 중 코드 파일의 줄 비율이고, 문서·설정·에셋과 확장자가 없는 파일(점으로 시작하는 숨김 파일 포함)은 코드로 세지 않는다")
    void codeRatioCountsOnlyCodeFileLines() {

        CandidateMeta meta = CandidateMeta.of(List.of(
                commit(file("src/Session.java", 30, 10), file("README.md", 15, 2), file("app/View.TSX", 10, 0)),
                commit(file("Makefile", 4, 1), file("bin/sh", 3, 0), file("scripts/.sh", 2, 1),
                        file("Assets/Intro.unity", 20, 2))));

        assertThat(meta.codeRatio()).isEqualByComparingTo("0.5");

    }

    @Test
    @DisplayName("코드 비율은 소수 넷째 자리까지 반올림한다")
    void codeRatioRoundsToFourPlaces() {

        CandidateMeta twoThirds = CandidateMeta.of(List.of(commit(file("a.java", 2, 0), file("b.md", 1, 0))));
        CandidateMeta oneThird = CandidateMeta.of(List.of(commit(file("a.java", 1, 0), file("b.md", 2, 0))));

        assertThat(twoThirds.codeRatio()).isEqualByComparingTo("0.6667");
        assertThat(oneThird.codeRatio()).isEqualByComparingTo("0.3333");

    }

    @Test
    @DisplayName("바뀐 파일 정보가 없거나 바뀐 줄이 하나도 없으면 코드 비율은 비운다")
    void codeRatioNeedsChangedLines() {

        assertThat(CandidateMeta.of(List.of(commit(MONDAY, 3))).codeRatio()).isNull();
        assertThat(CandidateMeta.of(List.of(commit(file("docs/logo.png", 0, 0)))).codeRatio()).isNull();

    }

    @Test
    @DisplayName("커밋 표시 경로는 그 커밋에서 바뀐 줄이 가장 많은 파일이다")
    void displayPathIsFileWithMostChangedLines() {

        StoredCommit commit = commit(file("src/Small.java", 5, 5), file("src/Big.java", 20, 1), file("docs/a.md", 3, 0));

        assertThat(CandidateMeta.displayPathOf(commit)).isEqualTo("src/Big.java");

    }

    @Test
    @DisplayName("표시 경로를 고를 때 바뀐 줄은 더한 줄과 뺀 줄을 합친 수다")
    void displayPathCountsDeletedLines() {

        StoredCommit commit = commit(file("src/Added.java", 10, 0), file("src/Removed.java", 2, 30));

        assertThat(CandidateMeta.displayPathOf(commit)).isEqualTo("src/Removed.java");

    }

    @Test
    @DisplayName("바뀐 줄이 같으면 경로 사전순으로 앞선 파일을 표시 경로로 쓴다")
    void displayPathBreaksTiesByPath() {

        StoredCommit commit = commit(file("src/z.java", 6, 4), file("src/a.java", 10, 0), file("src/m.java", 1, 0));

        assertThat(CandidateMeta.displayPathOf(commit)).isEqualTo("src/a.java");

    }

    @Test
    @DisplayName("바뀐 파일 정보가 없는 커밋의 표시 경로는 빈 글자다")
    void displayPathIsEmptyWithoutFiles() {

        assertThat(CandidateMeta.displayPathOf(commit(MONDAY, 3))).isEmpty();

    }
}
