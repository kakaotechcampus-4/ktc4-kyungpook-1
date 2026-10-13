package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.OwnCommitFile;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 내 커밋이 바꾼 파일을 폴더 끝 두 단계로 묶어 가장 많이 만진 폴더 다섯 개를 고른다
 * 폴더 경로는 후보 제목 '경로 작업' 이 후보 제목 길이 안에 들어야 해서 그 길이를 넘는 폴더는 뺀다
 */
final class RecallDirs {

    static final String TITLE_SUFFIX = " 작업";
    static final int MAX_PATH_LENGTH = CandidateText.TITLE_MAX_LENGTH - TITLE_SUFFIX.length();
    static final int MAX_DIRS = 5;
    static final int MAX_FILES = 5;
    static final String QUESTION = "이 파일들을 만졌을 때, 무엇을 해내려던 중이었나요?";
    static final List<String> OPTIONS = List.of("기능을 맡아 만들었어요", "팀원 코드를 이어받았어요", "급하게 버그를 고쳤어요");

    private static final Set<String> SKIPPED_FOLDERS = Set.of("out", "build", "target", "dist", "node_modules",
            ".idea", ".gradle");
    private static final int KEPT_FOLDER_LEVELS = 2;

    private RecallDirs() {
    }

    static List<RecallDir> of(List<OwnCommitFile> files) {

        Map<String, Map<String, Set<Long>>> commitsByPathInDir = new HashMap<>();
        for (OwnCommitFile file : files) {
            List<String> parts = partsOf(file.path());
            if (parts.isEmpty() || isSkipped(parts)) {
                continue;
            }
            String dir = dirOf(parts);
            if (dir.length() <= MAX_PATH_LENGTH) {
                commitsByPathInDir.computeIfAbsent(dir, key -> new HashMap<>())
                        .computeIfAbsent(String.join("/", parts), key -> new HashSet<>())
                        .add(file.commitId());
            }
        }

        return commitsByPathInDir.entrySet().stream()
                .map(entry -> summaryOf(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(RecallDir::myCommits).reversed()
                        .thenComparing(Comparator.comparingInt(RecallDir::filesChanged).reversed())
                        .thenComparing(RecallDir::path))
                .limit(MAX_DIRS)
                .toList();

    }

    private static RecallDir summaryOf(String path, Map<String, Set<Long>> commitsByPath) {

        Set<Long> allCommits = new HashSet<>();
        Map<String, Set<Long>> commitsByName = new HashMap<>();
        commitsByPath.forEach((filePath, commits) -> {
            allCommits.addAll(commits);
            commitsByName.computeIfAbsent(nameOf(filePath), key -> new HashSet<>()).addAll(commits);
        });
        List<String> names = commitsByName.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Set<Long>> entry) -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_FILES)
                .map(Map.Entry::getKey)
                .toList();

        return new RecallDir(path, commitsByPath.size(), allCommits.size(), names, QUESTION, OPTIONS);

    }

    private static List<String> partsOf(String path) {

        return Arrays.stream(path.split("/")).filter(part -> !part.isEmpty()).toList();

    }

    private static String nameOf(String path) {

        List<String> parts = partsOf(path);

        return parts.get(parts.size() - 1);

    }

    private static boolean isSkipped(List<String> parts) {

        String name = parts.get(parts.size() - 1);

        return name.endsWith(".class") || name.equals(".DS_Store")
                || parts.subList(0, parts.size() - 1).stream().anyMatch(SKIPPED_FOLDERS::contains);

    }

    private static String dirOf(List<String> parts) {

        List<String> folders = parts.subList(0, parts.size() - 1);
        if (folders.isEmpty()) {
            return "/";
        }

        return "/" + String.join("/", folders.subList(Math.max(0, folders.size() - KEPT_FOLDER_LEVELS), folders.size()))
                + "/";

    }
}
