package com.gitory.backend.coverletter.domain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카드 고르기·초안 쓰기가 도는 동안 AI 가 보내는 단계와 문항별 글을 서버 메모리에만 두었다가 자소서 조회에 싣고, 단계가 끝나면 지운다
 * 서버가 1대라는 전제다 — 2대로 늘리면 다른 서버가 받은 진행이 보이지 않으므로 Redis 로 옮긴다
 */
@Component
class CoverLetterLiveLog {

    private final Map<Long, CoverLetterLive> byLetter = new ConcurrentHashMap<>();

    CoverLetterLive start(Long letterId) {

        CoverLetterLive live = new CoverLetterLive();
        byLetter.put(letterId, live);
        return live;

    }

    // 멈춘 단계로 실패 처리된 뒤 다시 시도로 새로 집힌 단계가 있으면, 늦게 끝난 앞 단계가 새 진행을 지우지 않게 한다
    void finish(Long letterId, CoverLetterLive live) {

        byLetter.remove(letterId, live);

    }

    Optional<CoverLetterLive.Snapshot> of(Long letterId) {

        return Optional.ofNullable(byLetter.get(letterId)).map(CoverLetterLive::snapshot);

    }
}
