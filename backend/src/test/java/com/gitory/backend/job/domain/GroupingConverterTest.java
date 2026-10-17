package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gitory.backend.agent.port.ExperienceCandidate;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.agent.port.ExperienceGroupingResponse;
import com.gitory.backend.agent.port.ExperienceSourceType;
import com.gitory.backend.agent.port.GroupingCommit;
import com.gitory.backend.agent.port.GroupingFile;
import com.gitory.backend.agent.port.GroupingFileStatus;
import com.gitory.backend.agent.port.GroupingIssue;
import com.gitory.backend.agent.port.GroupingPullRequest;
import com.gitory.backend.agent.port.GroupingVerdict;
import com.gitory.backend.ingest.domain.FileChangeStatus;
import com.gitory.backend.ingest.domain.GroupingMaterial;
import com.gitory.backend.ingest.domain.MaterialCommit;
import com.gitory.backend.ingest.domain.MaterialFile;
import com.gitory.backend.ingest.domain.MaterialIssue;
import com.gitory.backend.ingest.domain.MaterialPullRequest;
import com.gitory.backend.recommend.domain.CandidateType;
import com.gitory.backend.recommend.domain.GroupedCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

class GroupingConverterTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    // 한국 시각으로는 다음 날 오전 8시 15분이다
    private static final Instant AUTHORED = Instant.parse("2026-09-22T23:15:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("요청에는 수집 요청과 같은 저장소 연결 id, 받은 사용자명·부분 수집 여부와 재료의 커밋·PR·이슈를 재료 순서대로 담는다")
    void requestCarriesConnectionLoginPartialAndMaterialOrder(boolean partial) {

        ExperienceGroupingRequest request = GroupingConverter.requestOf(15L, "Grow22", material(), partial);

        assertThat(request.repositoryId()).isEqualTo("15");
        assertThat(request.targetLogin()).isEqualTo("Grow22");
        assertThat(request.collectionPartial()).isEqualTo(partial);
        assertThat(request.commits()).extracting(GroupingCommit::sha).containsExactly(SHA_A, SHA_B);
        assertThat(request.pullRequests()).extracting(GroupingPullRequest::number).containsExactly(17, 21);
        assertThat(request.issues()).extracting(GroupingIssue::number).containsExactly(42, 43);

    }

    @Test
    @DisplayName("커밋 작성 시각은 +09:00 한국 시각으로 보내 한국 날짜가 그대로 보인다")
    void sendsAuthoredAtInKoreanTime() {

        OffsetDateTime authoredAt = GroupingConverter.requestOf(15L, "Grow22", material(), false)
                .commits().get(0).authoredAt();

        assertThat(authoredAt).isEqualTo(OffsetDateTime.parse("2026-09-23T08:15:00+09:00"));
        assertThat(authoredAt.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(authoredAt.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 23));

    }

    @Test
    @DisplayName("커밋 값은 저장된 그대로 보내고, 작성자 이메일·커밋 저장소 id·제외 판정과 파일의 코드 내용은 비워 보낸다")
    void copiesStoredCommitValuesAndLeavesUnknownEmpty() {

        List<GroupingCommit> commits = GroupingConverter.requestOf(15L, "Grow22", material(), false).commits();

        assertThat(commits).containsExactly(
                new GroupingCommit(SHA_A, null, "feat: 세션 테이블에 인덱스 추가 (#42)", "grow22", "Grow", null,
                        OffsetDateTime.parse("2026-09-23T08:15:00+09:00"), 1, 40, 3, 2, List.of(
                                new GroupingFile("db/V3__session_index.sql", GroupingFileStatus.ADDED, 12, 0, null),
                                new GroupingFile("session/SessionRepository.java", GroupingFileStatus.MODIFIED, 28, 3,
                                        null)),
                        17, List.of(42), null, null),
                new GroupingCommit(SHA_B, null, "", null, "장성준", null,
                        OffsetDateTime.parse("2026-09-23T08:16:00+09:00"), 2, null, null, null, List.of(), null,
                        List.of(), null, null));

    }

    @Test
    @DisplayName("PR·이슈 문맥은 번호·제목·본문 앞부분·연결 이슈를 재료 그대로 보낸다")
    void copiesPullRequestAndIssueContext() {

        ExperienceGroupingRequest request = GroupingConverter.requestOf(15L, "Grow22", material(), false);

        assertThat(request.pullRequests()).containsExactly(
                new GroupingPullRequest(17, "세션 조회 성능 개선", "Resolves #42", List.of(42)),
                new GroupingPullRequest(21, "로그인 화면 정리", null, List.of()));
        assertThat(request.issues()).containsExactly(
                new GroupingIssue(42, "로그인 뒤 첫 화면이 느립니다", "세션 조회에 3초 넘게 걸립니다."),
                new GroupingIssue(43, "로그아웃 버튼", null));

    }

    @ParameterizedTest
    @EnumSource(FileChangeStatus.class)
    @DisplayName("파일 변경 상태는 같은 이름의 값으로 보낸다")
    void sendsFileStatusBySameName(FileChangeStatus status) {

        MaterialCommit commit = new MaterialCommit(SHA_A, "fix", "grow22", null, AUTHORED, 1, 1, 0, 1,
                List.of(new MaterialFile("README.md", status, 1, 0)), null, List.of());

        GroupingFile file = GroupingConverter.requestOf(15L, "grow22",
                new GroupingMaterial(List.of(commit), List.of(), List.of()), false).commits().get(0).files().get(0);

        assertThat(file.status().name()).isEqualTo(status.name());

    }

    @Test
    @DisplayName("AI 경험 그룹화가 준 후보는 열쇠를 빼고 받은 순서·값 그대로 저장할 후보로 바꾸고, 판정과 AI 가 뺀 커밋은 옮기지 않는다")
    void convertsCandidatesWithoutGroupKey() {

        ExperienceGroupingResponse response = new ExperienceGroupingResponse(GroupingVerdict.PARTIAL, List.of(
                new ExperienceCandidate("pr:17", ExperienceSourceType.PR, "#17", 17, List.of(17, 21), List.of(42),
                        "세션 조회 성능 개선", "리뷰 코멘트가 많았던 작업", 0.75, false, List.of(SHA_A)),
                new ExperienceCandidate("cluster:2026-09-23:bbbbbbbb", ExperienceSourceType.COMMIT_CLUSTER,
                        "2026-09-23", null, List.of(), List.of(), "빌드 정리", "같은 날 이어진 작업", 0.2, true,
                        List.of(SHA_B))),
                List.of("c".repeat(40)));

        assertThat(GroupingConverter.candidatesOf(response)).containsExactly(
                new GroupedCandidate(CandidateType.PR, "#17", 17, List.of(17, 21), List.of(42), "세션 조회 성능 개선",
                        "리뷰 코멘트가 많았던 작업", 0.75, false, List.of(SHA_A)),
                new GroupedCandidate(CandidateType.COMMIT_CLUSTER, "2026-09-23", null, List.of(), List.of(), "빌드 정리",
                        "같은 날 이어진 작업", 0.2, true, List.of(SHA_B)));

    }

    @Test
    @DisplayName("같은 재료로 만든 요청은 늘 같은 JSON 글자가 된다")
    void sameRequestBecomesSameJson() {

        String first = GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", material(), false));
        String second = GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", material(), false));

        assertThat(first).isEqualTo(second);

    }

    @Test
    @DisplayName("요청 값이 하나라도 다르면 JSON 글자도 달라진다")
    void anyChangedValueChangesJson() {

        GroupingMaterial stored = material();
        String base = GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", stored, false));
        MaterialCommit first = stored.commits().get(0);
        MaterialCommit moreLines = new MaterialCommit(first.sha(), first.message(), first.authorLogin(),
                first.authorName(), first.authoredAt(), first.parentCount(), first.additions() + 1, first.deletions(),
                first.changedFiles(), first.files(), first.pullRequestNumber(), first.issueNumbers());
        GroupingMaterial changedCommit = new GroupingMaterial(List.of(moreLines, stored.commits().get(1)),
                stored.pullRequests(), stored.issues());
        GroupingMaterial changedPullRequest = new GroupingMaterial(stored.commits(),
                List.of(new MaterialPullRequest(17, "제목을 고친 PR", "Resolves #42", List.of(42)),
                        stored.pullRequests().get(1)), stored.issues());

        assertThat(List.of(
                GroupingConverter.jsonOf(GroupingConverter.requestOf(16L, "Grow22", stored, false)),
                GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "teammate", stored, false)),
                GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", stored, true)),
                GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", changedCommit, false)),
                GroupingConverter.jsonOf(GroupingConverter.requestOf(15L, "Grow22", changedPullRequest, false))))
                .doesNotContain(base)
                .doesNotHaveDuplicates();

    }

    private static GroupingMaterial material() {

        MaterialCommit indexed = new MaterialCommit(SHA_A, "feat: 세션 테이블에 인덱스 추가 (#42)", "grow22", "Grow",
                AUTHORED, 1, 40, 3, 2, List.of(
                        new MaterialFile("db/V3__session_index.sql", FileChangeStatus.ADDED, 12, 0),
                        new MaterialFile("session/SessionRepository.java", FileChangeStatus.MODIFIED, 28, 3)),
                17, List.of(42));
        MaterialCommit unlinked = new MaterialCommit(SHA_B, "", null, "장성준", AUTHORED.plusSeconds(60), 2, null, null,
                null, List.of(), null, List.of());

        return new GroupingMaterial(List.of(indexed, unlinked),
                List.of(new MaterialPullRequest(17, "세션 조회 성능 개선", "Resolves #42", List.of(42)),
                        new MaterialPullRequest(21, "로그인 화면 정리", null, List.of())),
                List.of(new MaterialIssue(42, "로그인 뒤 첫 화면이 느립니다", "세션 조회에 3초 넘게 걸립니다."),
                        new MaterialIssue(43, "로그아웃 버튼", null)));

    }
}
