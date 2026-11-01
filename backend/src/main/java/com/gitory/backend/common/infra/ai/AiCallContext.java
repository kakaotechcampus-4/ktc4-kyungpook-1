package com.gitory.backend.common.infra.ai;

import java.util.function.Supplier;

/**
 * 지금 스레드가 처리 중인 Job·카드·자소서로, AI 호출 기록에 어느 것의 호출인지 함께 남기려고 둔다
 * AI 는 부른 스레드에서 끝날 때까지 기다리므로 스레드에 걸어 두면 되고, 일이 끝나면 앞의 값으로 되돌린다
 */
public final class AiCallContext {

    private static final ThreadLocal<Long> JOB = new ThreadLocal<>();
    private static final ThreadLocal<Long> CARD = new ThreadLocal<>();
    private static final ThreadLocal<Long> COVER_LETTER = new ThreadLocal<>();

    private AiCallContext() {
    }

    public static void runForJob(Long jobId, Runnable work) {

        Long previous = JOB.get();
        JOB.set(jobId);
        try {
            work.run();
        } finally {
            restore(JOB, previous);
        }

    }

    public static <T> T callForCard(Long cardId, Supplier<T> work) {

        Long previous = CARD.get();
        CARD.set(cardId);
        try {
            return work.get();
        } finally {
            restore(CARD, previous);
        }

    }

    public static void runForCoverLetter(Long coverLetterId, Runnable work) {

        Long previous = COVER_LETTER.get();
        COVER_LETTER.set(coverLetterId);
        try {
            work.run();
        } finally {
            restore(COVER_LETTER, previous);
        }

    }

    public static Long jobId() {

        return JOB.get();

    }

    public static Long cardId() {

        return CARD.get();

    }

    public static Long coverLetterId() {

        return COVER_LETTER.get();

    }

    private static void restore(ThreadLocal<Long> holder, Long previous) {

        if (previous == null) {
            holder.remove();
        } else {
            holder.set(previous);
        }

    }
}
