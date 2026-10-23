package com.gitory.backend.card.domain.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceShaMatcherTest {

    private static final String FIRST = "abc1234" + "0".repeat(33);
    private static final String SECOND = "abc1234" + "1".repeat(33);
    private static final String THIRD = "def5678" + "0".repeat(33);
    private static final Map<String, Long> COMMITS = Map.of(FIRST, 1L, SECOND, 2L, THIRD, 3L);

    @Test
    @DisplayName("글자까지 같은 sha 는 그 커밋으로 잇는다")
    void linksExactSha() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of(THIRD), COMMITS)).containsExactly(3L);

    }

    @Test
    @DisplayName("대소문자가 달라도 같은 sha 로 보고 잇는다")
    void ignoresCase() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of(THIRD.toUpperCase(Locale.ROOT)), COMMITS)).containsExactly(3L);

    }

    @Test
    @DisplayName("앞 7자 이상이 커밋 하나와만 맞으면 그 커밋으로 잇는다")
    void linksUniquePrefix() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of("def5678", "abc12340"), COMMITS)).containsExactly(3L, 1L);

    }

    @Test
    @DisplayName("앞부분이 커밋 둘 이상과 맞으면 어느 커밋인지 정할 수 없어 버린다")
    void dropsAmbiguousPrefix() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of("abc1234"), COMMITS)).isEmpty();

    }

    @Test
    @DisplayName("7자보다 짧은 sha 는 커밋 하나와만 맞아도 버린다")
    void dropsShortSha() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of("def567"), COMMITS)).isEmpty();

    }

    @Test
    @DisplayName("저장된 커밋에 없는 sha 는 버린다")
    void dropsUnknownSha() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of("9999999" + "0".repeat(33), "1234567"), COMMITS))
                .isEmpty();

    }

    @Test
    @DisplayName("같은 커밋을 가리키는 sha 가 여럿이면 처음 나온 자리에 한 번만 잇는다")
    void linksEachCommitOnce() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of(THIRD, "def5678", "DEF56780"), COMMITS))
                .containsExactly(3L);

    }

    @Test
    @DisplayName("이은 커밋은 sha 가 들어온 순서를 지킨다")
    void keepsInputOrder() {

        assertThat(EvidenceShaMatcher.commitIdsOf(List.of(THIRD, SECOND, FIRST), COMMITS))
                .containsExactly(3L, 2L, 1L);

    }
}
