package com.gitory.backend.card.domain.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 가 적은 근거 sha 를 보낸 커밋에 잇는 결정적 조회이며, 맞지 않는 sha 는 모델에 되묻지 않고 버린다
 * 7자보다 짧은 sha 는 지어낸 번호일 수 있어 AI 쪽 match_commit_sha 와 같은 하한을 둔다
 */
public final class EvidenceShaMatcher {

    private static final int MIN_PREFIX_LENGTH = 7;

    private EvidenceShaMatcher() {
    }

    /** 글자까지 같은 sha 가 없으면 앞부분이 커밋 하나와만 맞을 때 그 커밋으로 잇고, 같은 커밋은 처음 나온 자리에 한 번만 남긴다 */
    public static List<Long> commitIdsOf(List<String> shas, Map<String, Long> commitIdsBySha) {

        Set<Long> commitIds = new LinkedHashSet<>();
        for (String written : shas) {
            String sha = written.toLowerCase(Locale.ROOT);
            Long exact = commitIdsBySha.get(sha);
            if (exact != null) {
                commitIds.add(exact);
                continue;
            }
            if (sha.length() < MIN_PREFIX_LENGTH) {
                continue;
            }
            List<Long> matched = new ArrayList<>();
            commitIdsBySha.forEach((full, commitId) -> {
                if (full.startsWith(sha)) {
                    matched.add(commitId);
                }
            });
            if (matched.size() == 1) {
                commitIds.add(matched.getFirst());
            }
        }

        return List.copyOf(commitIds);

    }
}
