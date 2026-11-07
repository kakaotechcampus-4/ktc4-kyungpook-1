package com.gitory.backend.notice.domain;

import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, NoticeService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NoticeServiceTest {

    private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant FIRST_READ = Instant.parse("2026-11-02T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    NoticeService notices;

    @Autowired
    JdbcTemplate jdbc;

    private Long myId;
    private Long othersId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myId = fixtures.insertUser(1L, "grow22");
        othersId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("목록은 본인 알림만 최근 순으로 30개까지 준다")
    void listsOnlyMyNoticesNewestFirstUpToThirty() {

        for (int minute = 0; minute < 31; minute++) {
            insert(myId, BASE.plusSeconds(minute * 60L), null);
        }
        insert(othersId, BASE.plusSeconds(3600), null);

        List<NoticeView> listed = notices.list(myId);

        assertThat(listed).hasSize(30);
        assertThat(listed.getFirst().createdAt()).isEqualTo(BASE.plusSeconds(30 * 60L));
        assertThat(listed.getLast().createdAt()).isEqualTo(BASE.plusSeconds(60L));
        assertThat(listed).extracting(NoticeView::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());

    }

    @Test
    @DisplayName("공고 알림에 담은 postings 는 저장했다 읽어도 값 그대로이고 다른 종류의 알림은 postings 가 비어 있다")
    void postingsOfAMatchedNoticeSurviveStorage() {

        UUID postingId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-11-20T14:59:59Z");
        List<NoticePosting> postings = List.of(
                new NoticePosting(postingId, "토스", "백엔드 개발자", 4, 5, expiresAt),
                new NoticePosting(UUID.randomUUID(), "당근", "서버 개발자", 1, 2, null));

        notices.leave(myId, NoticeKind.POSTINGS_MATCHED, NoticePayload.matched(postings));
        notices.leave(myId, NoticeKind.ANALYSIS_FAILED,
                NoticePayload.failed(UUID.randomUUID(), "grow22/gitory", UUID.randomUUID(), "GITHUB_UNAUTHORIZED"));

        List<NoticeView> listed = notices.list(myId);
        assertThat(listed).extracting(NoticeView::kind)
                .containsExactlyInAnyOrder(NoticeKind.POSTINGS_MATCHED, NoticeKind.ANALYSIS_FAILED);
        assertThat(listed.stream().filter(view -> view.kind() == NoticeKind.POSTINGS_MATCHED).findFirst().orElseThrow()
                .payload().postings()).isEqualTo(postings);
        assertThat(listed.stream().filter(view -> view.kind() == NoticeKind.ANALYSIS_FAILED).findFirst().orElseThrow()
                .payload().postings()).isEmpty();

    }

    @Test
    @DisplayName("읽음 표시는 읽은 시각을 남기고, 이미 읽은 알림은 처음 읽은 시각을 그대로 둔다")
    void readKeepsTheFirstReadTime() {

        UUID unread = insert(myId, BASE, null);
        UUID alreadyRead = insert(myId, BASE.plusSeconds(60), FIRST_READ);

        notices.read(unread, myId);
        notices.read(alreadyRead, myId);

        assertThat(readAtOf(unread)).isAfter(BASE);
        assertThat(readAtOf(alreadyRead)).isEqualTo(FIRST_READ);

    }

    @Test
    @DisplayName("남의 알림과 없는 알림을 읽음으로 표시하려 하면 NoticeNotFoundException 이고 남의 알림은 바뀌지 않는다")
    void readRejectsOthersAndMissingNotices() {

        UUID others = insert(othersId, BASE, null);

        assertThatThrownBy(() -> notices.read(others, myId)).isInstanceOf(NoticeNotFoundException.class);
        assertThatThrownBy(() -> notices.read(UUID.randomUUID(), myId)).isInstanceOf(NoticeNotFoundException.class);

        assertThat(readAtOf(others)).isNull();

    }

    @Test
    @DisplayName("모두 읽음은 본인의 읽지 않은 알림만 읽음으로 바꾸고 이미 읽은 알림과 남의 알림은 건드리지 않는다")
    void readAllMarksOnlyMyUnreadNotices() {

        UUID unread = insert(myId, BASE, null);
        UUID alreadyRead = insert(myId, BASE.plusSeconds(60), FIRST_READ);
        UUID others = insert(othersId, BASE.plusSeconds(120), null);

        notices.readAll(myId);

        assertThat(readAtOf(unread)).isAfter(BASE);
        assertThat(readAtOf(alreadyRead)).isEqualTo(FIRST_READ);
        assertThat(readAtOf(others)).isNull();

    }

    private UUID insert(Long userId, Instant createdAt, Instant readAt) {

        UUID publicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification (public_id, user_id, kind, payload, created_at, read_at)
                VALUES (?, ?, 'CARDS_DRAFTED', '{"repoName":"grow22/gitory"}'::jsonb, ?, ?)
                """, publicId, userId, Timestamp.from(createdAt), readAt == null ? null : Timestamp.from(readAt));

        return publicId;

    }

    private Instant readAtOf(UUID publicId) {

        Timestamp readAt = jdbc.queryForObject("SELECT read_at FROM notification WHERE public_id = ?", Timestamp.class,
                publicId);

        return readAt == null ? null : readAt.toInstant();

    }
}
