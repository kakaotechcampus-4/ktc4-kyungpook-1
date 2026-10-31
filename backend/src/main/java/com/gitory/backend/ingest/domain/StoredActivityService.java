package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.StoredActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 저장소 연결 하나에 저장된 커밋·바뀐 파일·PR·PR 줄 댓글 수를 다른 모듈이 읽는 조회로, 후보를 저장하는 recommend 와 카드 초안을 만드는 job 이 쓴다 */
@Service
@RequiredArgsConstructor
public class StoredActivityService {

    private final StoredActivityRepository storedActivity;

    /** 요청한 sha 중 저장된 커밋만 돌려주며, 바뀐 파일을 아직 채우지 않은 커밋은 파일이 빈 목록이다 */
    @Transactional(readOnly = true)
    public List<StoredCommit> findCommits(Long userRepositoryId, Collection<String> shas) {

        List<GitCommit> commits = storedActivity.findCommits(userRepositoryId, shas);
        Map<Long, List<ChangedFile>> filesByCommit = storedActivity.findFiles(commits.stream().map(GitCommit::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(CommitFile::getCommitId, Collectors.mapping(
                        file -> new ChangedFile(file.getPath(), file.getAdditions(), file.getDeletions()),
                        Collectors.toList())));

        return commits.stream()
                .map(commit -> storedCommitOf(commit, filesByCommit.getOrDefault(commit.getId(), List.of())))
                .toList();

    }

    public List<String> findShas(Collection<Long> commitIds) {

        return storedActivity.findShas(commitIds);

    }

    /** 줄 댓글이 하나도 없는 PR 은 결과에 없다 */
    public Map<Integer, Long> countReviewComments(Long userRepositoryId, Collection<Integer> pullRequestNumbers) {

        return storedActivity.countReviewComments(userRepositoryId, pullRequestNumbers).stream()
                .collect(Collectors.toMap(ReviewCommentCount::pullRequestNumber, ReviewCommentCount::count));

    }

    /** 저장된 PR 이 없으면 빈 값이다 */
    @Transactional(readOnly = true)
    public Optional<StoredPullRequest> findPullRequest(Long userRepositoryId, int number) {

        return storedActivity.findPullRequest(userRepositoryId, number);

    }

    /** sha 칸은 CHAR(40) 이라 40자보다 짧은 sha 는 뒤에 공백이 붙어 나오므로, 요청한 sha 와 같게 비교되도록 지운다 */
    private static StoredCommit storedCommitOf(GitCommit commit, List<ChangedFile> files) {

        return new StoredCommit(commit.getId(), commit.getSha().stripTrailing(), commit.getAuthoredAt(),
                commit.getChangedFiles(), files);

    }
}
