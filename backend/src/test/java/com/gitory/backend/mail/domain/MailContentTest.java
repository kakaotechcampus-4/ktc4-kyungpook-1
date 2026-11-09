package com.gitory.backend.mail.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MailContentTest {

    private static final String BASE = "https://gitory.example";

    @Test
    @DisplayName("확인 메일은 시안의 제목과 문구를 담고 Gitory 열기는 앱 주소로, 메일 알림 끄기는 인적사항 설정으로 잇는다")
    void confirmationMailMatchesTheMock() {

        MailMessage mail = MailContent.optInConfirmation(BASE + "/");

        assertThat(mail.subject()).isEqualTo("[Gitory] 이제 이 주소로 맞는 공고를 알려 드려요");
        assertThat(mail.html())
                .contains("메일 알림을 켰어요")
                .contains("맞는 공고가 있는 날에만 보내요.")
                .contains("href=\"https://gitory.example\"")
                .contains(">Gitory 열기</a>")
                .contains("href=\"https://gitory.example/settings/profile\"")
                .contains(">메일 알림 끄기</a>");

    }

    @Test
    @DisplayName("새 공고 메일은 제목에 곳 수가 들어가고 공고마다 회사 제목 채운 필수 조건 서울 날짜 마감 공고 보기 주소를 담는다")
    void postingsMailListsEachPosting() {

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant lateNightUtc = Instant.parse("2026-11-20T16:30:00Z");

        MailMessage mail = MailContent.matchedPostings(BASE, List.of(
                new MailPosting(first, "가나다소프트", "백엔드 개발자", 3, 4, lateNightUtc),
                new MailPosting(second, "라마바테크", "서버 개발 인턴", 2, 3, null)));

        assertThat(mail.subject()).isEqualTo("[Gitory] 새 공고 중 잘 맞는 곳을 2곳 찾았어요");
        assertThat(mail.html())
                .contains("가나다소프트", "백엔드 개발자", "필수 조건 4개 중 3개를 채웠어요 · 11월 21일 마감")
                .contains("라마바테크", "서버 개발 인턴", "필수 조건 3개 중 2개를 채웠어요 · 마감일 없음")
                .contains("href=\"https://gitory.example/match/" + first + "\"")
                .contains("href=\"https://gitory.example/match/" + second + "\"")
                .contains("href=\"https://gitory.example/settings/profile\"");

    }

    @Test
    @DisplayName("회사 이름과 공고 제목에 든 HTML 글자는 태그가 되지 않고 그대로 보이게 바꿔 넣는다")
    void escapesHtmlInCompanyAndTitle() {

        MailMessage mail = MailContent.matchedPostings(BASE, List.of(
                new MailPosting(UUID.randomUUID(), "<b>A&B</b>", "<script>alert(1)</script>", 1, 1, null)));

        assertThat(mail.html())
                .contains("&lt;b&gt;A&amp;B&lt;/b&gt;", "&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>", "<b>A");

    }
}
