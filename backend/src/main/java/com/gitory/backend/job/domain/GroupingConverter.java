package com.gitory.backend.job.domain;

import com.gitory.backend.agent.port.ExperienceCandidate;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.agent.port.ExperienceGroupingResponse;
import com.gitory.backend.agent.port.GroupingCommit;
import com.gitory.backend.agent.port.GroupingFile;
import com.gitory.backend.agent.port.GroupingFileStatus;
import com.gitory.backend.agent.port.GroupingIssue;
import com.gitory.backend.agent.port.GroupingPullRequest;
import com.gitory.backend.ingest.domain.GroupingMaterial;
import com.gitory.backend.ingest.domain.MaterialCommit;
import com.gitory.backend.ingest.domain.MaterialFile;
import com.gitory.backend.ingest.domain.MaterialIssue;
import com.gitory.backend.ingest.domain.MaterialPullRequest;
import com.gitory.backend.recommend.domain.CandidateType;
import com.gitory.backend.recommend.domain.GroupedCandidate;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 저장된 커밋·PR·이슈를 AI 경험 그룹화 요청으로, 그 응답의 후보를 저장할 후보로 바꾼다
 * recommend 는 agent 를 참조할 수 없어 두 모듈을 엮는 job 이 바꾼다
 */
final class GroupingConverter {

    // AI 는 커밋 묶음의 날짜를 받은 시각 그대로의 날짜로 정해, UTC 로 보내면 한국 시각 오전 9시 전 커밋이 전날로 묶인다
    private static final ZoneOffset KOREA = ZoneOffset.ofHours(9);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GroupingConverter() {
    }

    /** 저장소 id 칸에는 수집 요청과 같은 저장소 연결 id 를 보낸다 */
    static ExperienceGroupingRequest requestOf(Long userRepositoryId, String login, GroupingMaterial material,
                                               boolean collectionPartial) {

        return new ExperienceGroupingRequest(String.valueOf(userRepositoryId), login,
                material.commits().stream().map(GroupingConverter::commitOf).toList(),
                material.pullRequests().stream().map(GroupingConverter::pullRequestOf).toList(),
                material.issues().stream().map(GroupingConverter::issueOf).toList(),
                collectionPartial);

    }

    /** 지문을 만들 요청 글자로, 같은 요청이면 늘 같은 글자가 나온다 */
    static String jsonOf(ExperienceGroupingRequest request) {

        return JSON.writeValueAsString(request);

    }

    /**
     * 열쇠·판정·AI 가 뺀 커밋은 옮기지 않는다
     * 다시 경험 그룹화를 하면 손대지 않은 후보를 통째로 바꾸고, 판정은 저장된 후보로 Spring 이 정한다
     */
    static List<GroupedCandidate> candidatesOf(ExperienceGroupingResponse response) {

        return response.candidates().stream().map(GroupingConverter::candidateOf).toList();

    }

    /**
     * 작성자 이메일은 저장하지 않고 커밋의 저장소 id 는 AI 경험 그룹화가 쓰지 않아 비운다
     * 제외 판정도 비워 AI 가 작성자 로그인으로 내 커밋인지 한 번 더 거르게 한다
     */
    private static GroupingCommit commitOf(MaterialCommit commit) {

        return new GroupingCommit(commit.sha(), null, commit.message(), commit.authorLogin(), commit.authorName(),
                null, commit.authoredAt().atOffset(KOREA), commit.parentCount(), commit.additions(),
                commit.deletions(), commit.changedFiles(),
                commit.files().stream().map(GroupingConverter::fileOf).toList(), commit.pullRequestNumber(),
                commit.issueNumbers(), null, null);

    }

    /** 바뀐 코드 내용은 경험 그룹화에 쓰지 않아 보내지 않는다 */
    private static GroupingFile fileOf(MaterialFile file) {

        return new GroupingFile(file.path(), GroupingFileStatus.valueOf(file.status().name()), file.additions(),
                file.deletions(), null);

    }

    private static GroupingPullRequest pullRequestOf(MaterialPullRequest pullRequest) {

        return new GroupingPullRequest(pullRequest.number(), pullRequest.title(), pullRequest.bodyExcerpt(),
                pullRequest.linkedIssueNumbers());

    }

    private static GroupingIssue issueOf(MaterialIssue issue) {

        return new GroupingIssue(issue.number(), issue.title(), issue.bodyExcerpt());

    }

    private static GroupedCandidate candidateOf(ExperienceCandidate candidate) {

        return new GroupedCandidate(CandidateType.valueOf(candidate.sourceType().name()), candidate.sourceRef(),
                candidate.pullRequestNumber(), candidate.pullRequestNumbers(), candidate.issueNumbers(),
                candidate.title(), candidate.reason(), candidate.score(), candidate.lowCardWorth(),
                candidate.commitShas());

    }
}
