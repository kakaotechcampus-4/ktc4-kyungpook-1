package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.ingest.domain.CommitSummary;
import com.gitory.backend.ingest.domain.RepositoryReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 커밋은 git_commit 을 ON DELETE RESTRICT 로 가리켜 API 로는 커밋이 사라진 근거를 만들 수 없어,
 * 커밋을 찾지 못한 경우는 응답을 만드는 자리에서 직접 확인한다
 */
class EvidenceResponseTest {

    private static final RepositoryReference REPOSITORY = new RepositoryReference(UUID.randomUUID(), "grow22", "gitory");

    @Test
    @DisplayName("커밋을 찾지 못한 커밋 근거는 sha·url 을 지어내지 않고 둘 다 null 로 둔다")
    void leavesShaAndUrlEmptyWhenCommitIsMissing() {

        EvidenceResponse evidence = EvidenceResponse.commit(StarField.A, null, REPOSITORY);

        assertThat(evidence.field()).isEqualTo(StarField.A);
        assertThat(evidence.type()).isEqualTo(EvidenceType.COMMIT);
        assertThat(evidence.authoredBy()).isEqualTo(AuthoredBy.AI);
        assertThat(evidence.sha()).isNull();
        assertThat(evidence.url()).isNull();

    }

    @Test
    @DisplayName("7자리보다 짧은 sha 는 자르지 않고 그대로 싣는다")
    void keepsShortShaAsIs() {

        CommitSummary commit = new CommitSummary(1L, "abc12", "fix", Instant.EPOCH);

        EvidenceResponse evidence = EvidenceResponse.commit(StarField.R, commit, REPOSITORY);

        assertThat(evidence.sha()).isEqualTo("abc12");
        assertThat(evidence.url()).isEqualTo("https://github.com/grow22/gitory/commit/abc12");

    }
}
