package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardTheme;
import com.gitory.backend.card.domain.CardThemesReady;
import com.gitory.backend.card.domain.ConfirmedCardThemeReader;
import com.gitory.backend.card.domain.ConfirmedCardThemes;
import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.notice.domain.NoticeService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, LetterCardSuggester.class, NoticeService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LetterCardSuggesterTest {

    private static final UUID CARD_PUBLIC_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CoverLetterRepository letters;

    @Autowired
    CoverLetterQuestionRepository questions;

    @Autowired
    LetterCardSuggester suggester;

    @MockitoBean
    ConfirmedCardThemeReader cardReader;

    private TestFixtures fixtures;
    private Long userId;
    private Long cardId;
    private Long otherCardId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        cardId = fixtures.insertCard(CARD_PUBLIC_ID, userId);
        otherCardId = fixtures.insertCard(UUID.randomUUID(), userId);
        when(cardReader.read(anyLong())).thenReturn(Optional.of(new ConfirmedCardThemes(cardId, CARD_PUBLIC_ID, userId,
                "결제 장애 대응", List.of(CardTheme.PROBLEM_SOLVING, CardTheme.CHALLENGE))));

    }

    @Test
    @DisplayName("카드 유형과 같은 유형의 문항이 있는 초안 자소서에는 문항·카드를 담은 알림 한 줄을 남기고, 공고가 있으면 회사 이름을 앞에 붙인다")
    void themeMatchLeavesOneNoticeWithPayload() {

        CoverLetterPosting posting = new CoverLetterPosting(UUID.randomUUID(), "가온소프트", "백엔드", List.of(), List.of());
        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드", null, List.of(), null, posting));
        markDrafted(letter.getId(), Instant.now());
        CoverLetterQuestion collaboration = question(letter.getId(), 1, "협업 경험을 쓰세요", QuestionTheme.COLLABORATION, null);
        CoverLetterQuestion solving = question(letter.getId(), 2, "x".repeat(50), QuestionTheme.PROBLEM_SOLVING, null);

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT kind, payload::text AS payload FROM notification");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("LETTER_CARD_SUGGESTED");
        String payload = rows.get(0).get("payload").toString();
        assertThat(payload).contains(letter.getPublicId().toString(), "가온소프트 백엔드", solving.getPublicId().toString(),
                "x".repeat(40), CARD_PUBLIC_ID.toString(), "결제 장애 대응");
        assertThat(payload).doesNotContain("x".repeat(41), collaboration.getPublicId().toString());

    }

    @Test
    @DisplayName("카드 유형과 같은 문항이 없으면 알림을 남기지 않는다")
    void noMatchingThemeLeavesNothing() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드", null, null));
        markDrafted(letter.getId(), Instant.now());
        question(letter.getId(), 1, "협업 경험을 쓰세요", QuestionTheme.COLLABORATION, null);
        question(letter.getId(), 2, "지원 동기를 쓰세요", QuestionTheme.MOTIVATION, null);

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        assertThat(noticeCount()).isZero();

    }

    @Test
    @DisplayName("이미 그 카드를 쓰는 문항은 건너뛰고 다음으로 맞는 문항을 알린다")
    void skipsQuestionAlreadyUsingTheCard() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드", null, null));
        markDrafted(letter.getId(), Instant.now());
        question(letter.getId(), 1, "문제 해결 1", QuestionTheme.PROBLEM_SOLVING, cardId);
        CoverLetterQuestion supporting = question(letter.getId(), 2, "도전 2", QuestionTheme.CHALLENGE, null);
        supporting.chooseCards(otherCardId, cardId);
        questions.saveAndFlush(supporting);
        CoverLetterQuestion free = question(letter.getId(), 3, "문제 해결 3", QuestionTheme.PROBLEM_SOLVING, otherCardId);

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM notification", String.class);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains(free.getPublicId().toString());

    }

    @Test
    @DisplayName("30일보다 오래됐거나 초안이 아닌 자소서는 건너뛴다")
    void skipsOldOrNotDraftedLetters() {

        CoverLetter old = letters.saveAndFlush(CoverLetter.start(userId, "오래된", null, null));
        markDrafted(old.getId(), Instant.now().minus(31, ChronoUnit.DAYS));
        question(old.getId(), 1, "문제 해결", QuestionTheme.PROBLEM_SOLVING, null);
        CoverLetter waiting = letters.saveAndFlush(CoverLetter.start(userId, "쓰는 중", null, null));
        question(waiting.getId(), 1, "문제 해결", QuestionTheme.PROBLEM_SOLVING, null);

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        assertThat(noticeCount()).isZero();

    }

    @Test
    @DisplayName("초안 자소서가 셋보다 많으면 가장 최근에 고친 셋에만 알린다")
    void onlyNewestThreeLetters() {

        for (int day = 1; day <= 4; day++) {
            CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "자소서 " + day, null, null));
            markDrafted(letter.getId(), Instant.now().minus(day, ChronoUnit.DAYS));
            question(letter.getId(), 1, "문제 해결", QuestionTheme.PROBLEM_SOLVING, null);
        }

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM notification", String.class);
        assertThat(payloads).hasSize(3);
        assertThat(String.join("", payloads)).doesNotContain("자소서 4");

    }

    @Test
    @DisplayName("같은 카드로 다시 돌려도 읽지 않은 같은 알림이 있으면 새로 남기지 않고, 읽은 뒤에는 다시 남긴다")
    void doesNotStackUnreadDuplicates() {

        CoverLetter letter = letters.saveAndFlush(CoverLetter.start(userId, "백엔드", null, null));
        markDrafted(letter.getId(), Instant.now());
        question(letter.getId(), 1, "문제 해결", QuestionTheme.PROBLEM_SOLVING, null);

        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));
        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        assertThat(noticeCount()).isEqualTo(1);

        jdbc.update("UPDATE notification SET read_at = now()");
        suggester.leaveNoticesFor(new CardThemesReady(cardId, userId));

        assertThat(noticeCount()).isEqualTo(2);

    }

    private CoverLetterQuestion question(Long letterId, int seq, String text, QuestionTheme theme, Long primaryCardId) {

        CoverLetterQuestion question = CoverLetterQuestion.of(letterId, seq, text, 500);
        question.assign(theme, primaryCardId, null, AssignedBy.CODE, null, List.of());

        return questions.saveAndFlush(question);

    }

    private void markDrafted(Long letterId, Instant updatedAt) {

        jdbc.update("UPDATE cover_letter SET stage = 'DRAFTED', updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), letterId);

    }

    private int noticeCount() {

        return jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class);

    }
}
