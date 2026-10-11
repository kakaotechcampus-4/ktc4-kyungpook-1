package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/**
 * 수집한 커밋 하나가 바꾼 파일 한 개를 담는다
 * 커밋마다 앞부분 파일만 저장할 수 있어, 바뀐 파일이 모두 몇 개인지는 git_commit.changed_files 로 본다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "commit_file")
public class CommitFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long commitId;

    private String path;

    @Enumerated(EnumType.STRING)
    private FileChangeStatus status;

    private int additions;
    private int deletions;

    private CommitFile(Long commitId, String path, FileChangeStatus status, int additions, int deletions) {
        this.commitId = commitId;
        this.path = path;
        this.status = status;
        this.additions = additions;
        this.deletions = deletions;
    }

    public static CommitFile collected(Long commitId, String path, FileChangeStatus status, int additions,
                                       int deletions) {

        return new CommitFile(commitId, path, status, additions, deletions);

    }
}
