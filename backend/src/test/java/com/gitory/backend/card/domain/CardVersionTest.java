package com.gitory.backend.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class CardVersionTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-09T01:02:03.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-09T01:02:03.123456Z");

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"AI_DRAFT, false", "USER_EDIT, true", "INTERVIEW, false", "MASK, false", "RESTORE, false"})
    @DisplayName("확정본이 아닌 직접 수정 버전만 임시 저장이 덮어쓸 수 있고, 확정본은 출처와 상관없이 덮어쓸 수 없다")
    void onlyUnconfirmedUserEditIsOverwritable(VersionSource source, boolean overwritable) {

        CardVersion version = CardVersion.of(1L, (short) 1, source);

        assertThat(version.isOverwritable()).isEqualTo(overwritable);

        version.markConfirmed();
        assertThat(version.isOverwritable()).isFalse();

    }

    @Test
    @DisplayName("덮어쓴 직접 수정 버전에 저장 표시를 하면 그 버전의 시각이 마지막 저장 시각으로 바뀐다")
    void markSavedRefreshesTime() {

        CardVersion version = CardVersion.of(1L, (short) 2, VersionSource.USER_EDIT);
        ReflectionTestUtils.setField(version, "updatedAt", OLD);

        version.markSaved();

        assertThat(version.getUpdatedAt()).isAfter(OLD);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockedVersions")
    @DisplayName("덮어쓸 수 없는 버전에 저장 표시를 하면 IllegalStateException 이고 시각이 바뀌지 않는다")
    void markSavedRejectsLockedVersion(CardVersion version) {

        ReflectionTestUtils.setField(version, "updatedAt", OLD);

        assertThatThrownBy(version::markSaved).isInstanceOf(IllegalStateException.class);
        assertThat(version.getUpdatedAt()).isEqualTo(OLD);

    }

    @Test
    @DisplayName("버전을 만들 때와 저장 표시를 할 때 남기는 시각은 지금 시각에서 µs 아래를 잘라 낸 값이다")
    void recordsTimesInMicros() {

        // 실제 시계는 µs 아래가 0 인 값을 줄 때가 있어 잘라 냈는지 가릴 수 없으므로, 지금 시각을 ns 자리까지 있는 값으로 정해 둔다
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(NOW);

            CardVersion version = CardVersion.of(1L, (short) 2, VersionSource.USER_EDIT);
            assertThat(version.getUpdatedAt()).isEqualTo(NOW_IN_MICROS);

            ReflectionTestUtils.setField(version, "updatedAt", OLD);
            version.markSaved();
            assertThat(version.getUpdatedAt()).isEqualTo(NOW_IN_MICROS);
        }

    }

    static Stream<Named<CardVersion>> lockedVersions() {

        CardVersion confirmedEdit = CardVersion.of(1L, (short) 3, VersionSource.USER_EDIT);
        confirmedEdit.markConfirmed();

        return Stream.of(
                Named.of("AI 초안", CardVersion.of(1L, (short) 1, VersionSource.AI_DRAFT)),
                Named.of("답변 반영", CardVersion.of(1L, (short) 2, VersionSource.INTERVIEW)),
                Named.of("확정한 직접 수정", confirmedEdit));

    }
}
