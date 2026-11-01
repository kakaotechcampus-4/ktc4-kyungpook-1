package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CollectionCounts;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.Contribution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 경험 그룹화를 했는데 후보가 하나도 없을 때 화면에 보일 이유 줄을 저장된 실제 숫자로 만든다
 * 후보 보드와 분석 Job 의 결과가 같은 줄을 보이도록 이 한 곳에서만 만들고, 앞날을 약속하는 문장은 넣지 않는다
 */
@Service
@RequiredArgsConstructor
public class CandidateEmptyReasons {

    private final CollectionStatsService stats;

    public List<String> of(UUID repositoryId, Long userId, String login) {

        CollectionCounts counts = stats.counts(repositoryId, userId);
        boolean counted = counts.countedAt() != null;
        List<String> reasons = new ArrayList<>();

        reasons.add(commitLine(counts, counted));
        List<String> messages = stats.ownCommitMessages(repositoryId, userId, login);
        if (!messages.isEmpty()) {
            reasons.add("커밋 메시지 평균 " + averageFirstLineLength(messages) + "글자");
        }
        if (counted) {
            reasons.add(pullRequestLine(counts));
        }
        int withoutAccount = stats.commitsWithoutAuthorAccount(repositoryId, userId);
        if (withoutAccount >= 1) {
            reasons.add("계정에 연결되지 않은 커밋 " + withoutAccount + "개는 내 커밋으로 세지 않았어요");
        }

        return reasons;

    }

    /** 개수를 세지 못한 저장소의 0 은 실제 0 과 구분되지 않아 숫자를 보이지 않는다 */
    private static String commitLine(CollectionCounts counts, boolean counted) {

        if (!counted) {
            return "내 커밋 수를 아직 세지 못했어요";
        }
        Contribution contribution = counts.contribution();

        return "내 커밋 " + contribution.mine() + "개 / 팀 " + contribution.team() + "개";

    }

    private static String pullRequestLine(CollectionCounts counts) {

        if (counts.ownPrCount() == 0 && counts.reviewedPrCount() == 0) {
            return "PR 참여 없음";
        }

        return "PR " + counts.ownPrCount() + "건 · 리뷰 " + counts.reviewedPrCount() + "건";

    }

    private static long averageFirstLineLength(List<String> messages) {

        long total = messages.stream().mapToLong(CandidateEmptyReasons::firstLineLength).sum();

        return Math.round((double) total / messages.size());

    }

    /** 이모지처럼 두 칸을 차지하는 글자도 하나로 센다 */
    private static int firstLineLength(String message) {

        String firstLine = message.lines().findFirst().orElse("");

        return firstLine.codePointCount(0, firstLine.length());

    }
}
