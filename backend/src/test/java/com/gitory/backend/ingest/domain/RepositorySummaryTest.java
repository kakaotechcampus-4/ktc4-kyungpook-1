package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class RepositorySummaryTest {

    @Test
    @DisplayName("사용자가 쓴 설명이 있으면 GitHub 설명과 README 문장이 있어도 사용자가 쓴 설명을 고른다")
    void userDescriptionComesFirst() {

        RepositorySummary summary = summary("내가 쓴 설명", "GitHub 설명", "README 문장");

        assertThat(summary.description()).isEqualTo("내가 쓴 설명");
        assertThat(summary.descriptionSource()).isEqualTo(DescriptionSource.USER);

    }

    @Test
    @DisplayName("사용자가 쓴 설명이 없으면 GitHub 설명을 README 문장보다 먼저 고른다")
    void githubDescriptionComesBeforeReadme() {

        RepositorySummary summary = summary(null, "GitHub 설명", "README 문장");

        assertThat(summary.description()).isEqualTo("GitHub 설명");
        assertThat(summary.descriptionSource()).isEqualTo(DescriptionSource.GITHUB);

    }

    @Test
    @DisplayName("사용자가 쓴 설명도 GitHub 설명도 없으면 README 문장을 고른다")
    void readmeSummaryComesLast() {

        RepositorySummary summary = summary(null, null, "README 문장");

        assertThat(summary.description()).isEqualTo("README 문장");
        assertThat(summary.descriptionSource()).isEqualTo(DescriptionSource.README);

    }

    @Test
    @DisplayName("셋 다 없으면 설명도 출처도 null 이다")
    void noDescriptionWhenAllMissing() {

        RepositorySummary summary = summary(null, null, null);

        assertThat(summary.description()).isNull();
        assertThat(summary.descriptionSource()).isNull();

    }

    @Test
    @DisplayName("공백뿐인 칸은 없는 것으로 보고 다음 순서로 넘어간다")
    void blankIsSkipped() {

        RepositorySummary summary = summary("   ", "", "README 문장");

        assertThat(summary.description()).isEqualTo("README 문장");
        assertThat(summary.descriptionSource()).isEqualTo(DescriptionSource.README);

    }

    private static RepositorySummary summary(String user, String github, String readme) {

        return new RepositorySummary(1L, UUID.randomUUID(), "grow22", "gitory", "Java", null, null, null, null,
                0, 0, 0, 0, 0, user, github, readme);

    }
}
