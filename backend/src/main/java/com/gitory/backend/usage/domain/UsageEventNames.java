package com.gitory.backend.usage.domain;

import java.util.Set;

/**
 * 이름을 늘릴 때마다 DB 마이그레이션이 필요 없도록 허용 목록은 DB 가 아니라 여기에만 둔다
 * 화면의 track.ts 이름과 어긋나면 그 지표는 400 으로 버려지므로 이름을 늘릴 때 두 곳을 같이 고친다
 */
final class UsageEventNames {

    private static final Set<String> ALLOWED = Set.of(
            "repo_selected", "analysis_started", "candidates_shown", "candidate_excluded", "candidate_restored",
            "cluster_commit_excluded", "candidate_added_manually", "candidates_confirmed",
            "draft_generated", "interview_asked", "interview_answered", "card_edited", "card_masked",
            "card_confirmed", "card_reopened", "version_restored", "manual_card_created", "field_regenerated",
            "recall_answered", "github_disconnected", "card_exported",
            "match_opened", "cover_letter_created", "cover_letter_copied", "cover_letter_edited");

    private UsageEventNames() {
    }

    static boolean isAllowed(String name) {

        return name != null && ALLOWED.contains(name);

    }
}
