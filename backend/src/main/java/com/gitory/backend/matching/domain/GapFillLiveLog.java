package com.gitory.backend.matching.domain;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모자란 조건 채우기가 도는 동안 AI 가 보낸 조건 상태를 서버 메모리에만 두었다가 공고 상세 조회에 싣고, 결과를 저장하면 지운다
 * 서버가 1대라는 전제다 — 2대로 늘리면 다른 서버가 받은 진행이 보이지 않으므로 Redis 로 옮긴다
 */
@Component
class GapFillLiveLog {

    private final Map<Key, Live> byPosting = new ConcurrentHashMap<>();

    Live start(Long userId, Long jobPostingId, List<GapFillCondition> waiting) {

        Live live = new Live(waiting);
        byPosting.put(new Key(userId, jobPostingId), live);
        return live;

    }

    // 멈춘 찾기를 다시 시작한 뒤 늦게 끝난 앞 찾기가 새 진행을 지우지 않게 한다
    void finish(Long userId, Long jobPostingId, Live live) {

        byPosting.remove(new Key(userId, jobPostingId), live);

    }

    Optional<List<GapFillCondition>> of(Long userId, Long jobPostingId) {

        return Optional.ofNullable(byPosting.get(new Key(userId, jobPostingId))).map(Live::snapshot);

    }

    /** AI 응답을 읽는 스레드가 쓰고 조회 요청이 읽어서 모든 메서드를 잠그며, 조건 순서는 시작할 때의 공고 조건 순서다 */
    static final class Live {

        private final Map<UUID, GapFillCondition> conditions = new LinkedHashMap<>();

        private Live(List<GapFillCondition> waiting) {
            waiting.forEach(condition -> conditions.put(condition.conditionId(), condition));
        }

        // AI 는 조건이 바뀔 때마다 그 조건의 지금 상태 전체를 보내므로 앞 상태를 통째로 바꾼다
        synchronized void update(GapFillCondition condition) {

            conditions.computeIfPresent(condition.conditionId(), (id, before) -> condition);

        }

        synchronized List<GapFillCondition> snapshot() {

            return List.copyOf(conditions.values());

        }
    }

    private record Key(Long userId, Long jobPostingId) {
    }
}
