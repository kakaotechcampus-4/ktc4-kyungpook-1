package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardTheme;
import com.gitory.backend.card.domain.CardThemesReady;
import com.gitory.backend.card.domain.ConfirmedCardThemeReader;
import com.gitory.backend.card.domain.ConfirmedCardThemes;
import com.gitory.backend.coverletter.infra.CoverLetterQuestionRepository;
import com.gitory.backend.coverletter.infra.CoverLetterRepository;
import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 카드 유형이 정해지면 최근에 초안이 나온 자소서에서 그 유형의 문항을 찾아, 다시 쓸 만하다고 알림 한 줄을 남긴다
 * AI 를 부르지 않고 유형이 같은지만 보며, 글은 사용자가 눌러야 바뀐다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LetterCardSuggester {

    static final Duration RECENT = Duration.ofDays(30);
    static final int QUESTION_PREVIEW = 40;

    private final ConfirmedCardThemeReader cardReader;
    private final CoverLetterRepository letters;
    private final CoverLetterQuestionRepository questions;
    private final NoticeService notices;
    private final Clock clock;

    @Async
    @TransactionalEventListener
    public void suggest(CardThemesReady event) {

        leaveNoticesFor(event);

    }

    /** 끝나기를 기다릴 수 있는 호출로, 어떤 실패도 밖으로 던지지 않고 알림을 남기지 않는다 */
    public void leaveNoticesFor(CardThemesReady event) {

        try {
            cardReader.read(event.cardId()).ifPresent(this::leaveNotices);
        } catch (RuntimeException failure) {
            log.warn("카드 {} 에 맞는 자소서 문항을 찾다 실패해 알림을 남기지 않는다", event.cardId(), failure);
        }

    }

    private void leaveNotices(ConfirmedCardThemes card) {

        Set<String> themeNames = card.themes().stream().map(CardTheme::name).collect(Collectors.toSet());
        Instant since = Instant.now(clock).minus(RECENT);

        for (CoverLetter letter : letters.findTop3ByUserIdAndStageAndUpdatedAtAfterOrderByUpdatedAtDescIdDesc(
                card.userId(), CoverLetterStage.DRAFTED, since)) {
            questions.findByCoverLetterIdOrderBySeq(letter.getId()).stream()
                    .filter(question -> question.getTheme() != null && themeNames.contains(question.getTheme().name()))
                    .filter(question -> !card.cardId().equals(question.getPrimaryCardId())
                            && !card.cardId().equals(question.getSupportingCardId()))
                    .findFirst()
                    .filter(question -> !alreadyUnread(card, letter, question))
                    .ifPresent(question -> notices.leave(card.userId(), NoticeKind.LETTER_CARD_SUGGESTED,
                            NoticePayload.letterCard(letter.getPublicId(), titleOf(letter), question.getPublicId(),
                                    clip(question.getText()), card.publicId(), card.title())));
        }

    }

    // 같은 카드를 다시 확정해도 아직 읽지 않은 같은 안내가 있으면 쌓지 않는다
    private boolean alreadyUnread(ConfirmedCardThemes card, CoverLetter letter, CoverLetterQuestion question) {

        return notices.list(card.userId()).stream()
                .filter(view -> view.kind() == NoticeKind.LETTER_CARD_SUGGESTED && view.readAt() == null)
                .anyMatch(view -> letter.getPublicId().equals(view.payload().letterId())
                        && question.getPublicId().equals(view.payload().questionId())
                        && card.publicId().equals(view.payload().cardId()));

    }

    private static String titleOf(CoverLetter letter) {

        CoverLetterPosting posting = letter.getPosting();

        return posting == null || posting.company() == null || posting.company().isBlank() ? letter.getJobRole()
                : posting.company() + " " + letter.getJobRole();

    }

    private static String clip(String text) {

        return text.length() <= QUESTION_PREVIEW ? text : text.substring(0, QUESTION_PREVIEW);

    }
}
