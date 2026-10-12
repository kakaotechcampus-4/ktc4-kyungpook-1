package com.gitory.backend.recommend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 저장소 연결 하나를 마지막으로 묶은 시각과 그 입력(AI 에 보내는 요청)의 지문·묶기 버전을 담는다
 * 지문과 묶기 버전이 지난번과 같으면 다시 묶지 않고, 묶은 시각이 마지막 분석보다 이르면 아직 못 묶은 저장소로 본다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "candidate_grouping")
public class CandidateGrouping {

    @Id
    private Long userRepositoryId;

    private String inputFingerprint;
    private int groupingVersion;
    private Instant groupedAt;

    private CandidateGrouping(Long userRepositoryId, String inputFingerprint, int groupingVersion) {
        this.userRepositoryId = userRepositoryId;
        this.inputFingerprint = inputFingerprint;
        this.groupingVersion = groupingVersion;
        this.groupedAt = Instant.now();
    }

    /** 이 저장소 연결을 처음 묶은 기록을 만든다 */
    public static CandidateGrouping of(Long userRepositoryId, String inputFingerprint, int groupingVersion) {

        return new CandidateGrouping(userRepositoryId, inputFingerprint, groupingVersion);

    }

    public boolean hasSameInput(String inputFingerprint, int groupingVersion) {

        return this.inputFingerprint.equals(inputFingerprint) && this.groupingVersion == groupingVersion;

    }

    /** 새로 묶었거나 입력이 같아 묶기를 건너뛰었을 때 부르며, 어느 쪽이든 묶은 시각을 지금으로 바꾼다 */
    public void record(String inputFingerprint, int groupingVersion) {

        this.inputFingerprint = inputFingerprint;
        this.groupingVersion = groupingVersion;
        this.groupedAt = Instant.now();

    }
}
