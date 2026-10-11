package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.ChangedFile;
import com.gitory.backend.ingest.domain.StoredCommit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

record CandidateMeta(int fileCount, Integer spanDays, BigDecimal codeRatio) {

    // 문서·설정·잠금 파일과 씬·이미지 같은 에셋은 바뀐 줄이 많아도 코드로 세지 않는다
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy", "py", "rb", "php", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs",
            "swift", "m", "mm", "dart", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "html", "css",
            "scss", "sass", "less", "sql", "sh", "lua", "r");

    static CandidateMeta of(List<StoredCommit> commits) {

        return new CandidateMeta(fileCountOf(commits), spanDaysOf(commits), codeRatioOf(commits));

    }

    /** 화면이 커밋마다 경로를 글자로만 받아, 바뀐 파일 정보가 없는 커밋은 빈 글자다 */
    static String displayPathOf(StoredCommit commit) {

        return commit.files().stream()
                .min(Comparator.comparingInt(ChangedFile::lines).reversed().thenComparing(ChangedFile::path))
                .map(ChangedFile::path)
                .orElse("");

    }

    /** 바뀐 파일을 아직 채우지 않은 옛 커밋뿐이면 경로를 모르므로, GitHub 이 커밋마다 준 바뀐 파일 수를 더한다 */
    private static int fileCountOf(List<StoredCommit> commits) {

        long paths = commits.stream()
                .flatMap(commit -> commit.files().stream())
                .map(ChangedFile::path)
                .distinct()
                .count();
        if (paths > 0) {
            return (int) paths;
        }

        return commits.stream()
                .map(StoredCommit::changedFiles)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

    }

    private static Integer spanDaysOf(List<StoredCommit> commits) {

        List<Instant> authored = commits.stream()
                .map(StoredCommit::authoredAt)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (authored.isEmpty()) {
            return null;
        }

        return (int) Duration.between(authored.getFirst(), authored.getLast()).toDays();

    }

    private static BigDecimal codeRatioOf(List<StoredCommit> commits) {

        List<ChangedFile> files = commits.stream().flatMap(commit -> commit.files().stream()).toList();
        long lines = files.stream().mapToLong(ChangedFile::lines).sum();
        if (lines == 0) {
            return null;
        }
        long codeLines = files.stream().filter(CandidateMeta::isCode).mapToLong(ChangedFile::lines).sum();

        return BigDecimal.valueOf(codeLines).divide(BigDecimal.valueOf(lines), 4, RoundingMode.HALF_UP);

    }

    private static boolean isCode(ChangedFile file) {

        String name = file.path().substring(file.path().lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');

        return dot > 0 && CODE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));

    }
}
