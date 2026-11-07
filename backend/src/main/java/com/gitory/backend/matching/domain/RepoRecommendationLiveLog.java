package com.gitory.backend.matching.domain;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 레포 추천을 계산하는 동안 후보 회사별 확인 상태와 맞은 조건을 서버 메모리에만 두었다가 레포 추천 조회에 싣는다
 * 끝나도 지우지 않아 늦게 연 화면도 과정을 볼 수 있고, 같은 레포의 다음 계산이 시작되면 지우고 후보를 정한 뒤 새 진행을 남긴다
 * 서버가 1대라는 전제다 — 2대로 늘리면 다른 서버가 받은 진행이 보이지 않으므로 Redis 로 옮긴다
 */
@Component
public class RepoRecommendationLiveLog {

    private final Map<Long, Live> byRepository = new ConcurrentHashMap<>();

    public Live start(Long userRepositoryId, int cardCount, List<String> techs,
                      List<RepoRecommendationLive.Candidate> candidates) {

        Live live = new Live(cardCount, techs, candidates);
        byRepository.put(userRepositoryId, live);
        return live;

    }

    void drop(Long userRepositoryId) {

        byRepository.remove(userRepositoryId);

    }

    Optional<RepoRecommendationLive> of(Long userRepositoryId) {

        return Optional.ofNullable(byRepository.get(userRepositoryId)).map(Live::snapshot);

    }

    /** 계산하는 스레드가 쓰고 조회 요청이 읽어서 모든 메서드를 잠그며, 후보 순서는 1단계가 고른 순서다 */
    public static final class Live {

        private final int cardCount;
        private final List<String> techs;
        private final List<RepoRecommendationLive.Candidate> candidates;
        private boolean finished;

        private Live(int cardCount, List<String> techs, List<RepoRecommendationLive.Candidate> candidates) {
            this.cardCount = cardCount;
            this.techs = List.copyOf(techs);
            this.candidates = new ArrayList<>(candidates);
        }

        synchronized void checking(int index) {

            replace(index, RepoRecommendationLiveState.CHECKING, false, List.of());

        }

        synchronized void done(int index, List<RepoRecommendationLive.Hit> hits) {

            replace(index, RepoRecommendationLiveState.DONE, false, hits);

        }

        synchronized void failed(int index) {

            replace(index, RepoRecommendationLiveState.DONE, true, List.of());

        }

        synchronized void finish() {

            finished = true;

        }

        synchronized RepoRecommendationLive snapshot() {

            return new RepoRecommendationLive(cardCount, techs, List.copyOf(candidates), finished);

        }

        private void replace(int index, RepoRecommendationLiveState state, boolean failed,
                             List<RepoRecommendationLive.Hit> hits) {

            RepoRecommendationLive.Candidate before = candidates.get(index);
            candidates.set(index, new RepoRecommendationLive.Candidate(before.companyId(), before.companyName(),
                    before.logoUrl(), before.posting(), before.overlap(), state, failed, List.copyOf(hits)));

        }
    }
}
