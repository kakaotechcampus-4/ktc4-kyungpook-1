package com.gitory.backend.recommend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import static lombok.AccessLevel.PROTECTED;

/**
 * 저장소 연결 하나에서 마지막으로 경험 그룹화를 한 시각과 그 입력(AI 에 보내는 요청)의 지문·경험 그룹화 버전을 담는다
 * 지문과 경험 그룹화 버전이 지난번과 같으면 다시 하지 않고, 경험 그룹화 시각이 마지막 분석보다 이르면 아직 경험 그룹화를 못 한 저장소로 본다
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

    /** 이 저장소 연결의 첫 경험 그룹화 기록을 만든다 */
    public static CandidateGrouping of(Long userRepositoryId, String inputFingerprint, int groupingVersion) {

        return new CandidateGrouping(userRepositoryId, inputFingerprint, groupingVersion);

    }

    /** 요청과 경험 그룹화 버전이 같으면 늘 같은 SHA-256 16진수 64자이고, 버전을 넣어 AI 규칙이 바뀌면 요청이 같아도 지문이 바뀐다 */
    public static String fingerprintOf(String request, int groupingVersion) {

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((groupingVersion + "\n" + request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }

    }

    public boolean hasSameInput(String inputFingerprint, int groupingVersion) {

        return this.inputFingerprint.equals(inputFingerprint) && this.groupingVersion == groupingVersion;

    }

    /** 새로 경험 그룹화를 했거나 입력이 같아 건너뛰었을 때 부르며, 어느 쪽이든 경험 그룹화 시각을 지금으로 바꾼다 */
    public void record(String inputFingerprint, int groupingVersion) {

        this.inputFingerprint = inputFingerprint;
        this.groupingVersion = groupingVersion;
        this.groupedAt = Instant.now();

    }
}
