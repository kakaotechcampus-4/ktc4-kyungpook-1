package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gitory.backend.ingest.domain.OwnCommitFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RecallDirsTest {

    @Test
    @DisplayName("파일이 든 폴더의 끝 두 단계로 묶고, 폴더가 한 단계면 그 폴더, 맨 위 파일이면 슬래시 하나로 묶는다")
    void groupsByLastTwoFolderLevels() {

        List<RecallDir> dirs = RecallDirs.of(List.of(
                file(1, "src/main/java/com/App.java"),
                file(2, "docs/README.md"),
                file(3, "build.gradle")));

        assertThat(dirs).extracting(RecallDir::path).containsExactlyInAnyOrder("/java/com/", "/docs/", "/");

    }

    @Test
    @DisplayName("out·build·target·dist·node_modules·.idea·.gradle 폴더 아래 파일과 .class 파일과 .DS_Store 는 건너뛴다")
    void skipsGeneratedFolders() {

        List<RecallDir> dirs = RecallDirs.of(List.of(
                file(1, "out/Foo.java"),
                file(1, "app/build/gen/Gen.java"),
                file(1, "web/node_modules/lib/index.js"),
                file(1, "app/.idea/workspace.xml"),
                file(1, ".gradle/caches/x.bin"),
                file(1, "app/target/classes/Main.java"),
                file(1, "web/dist/bundle.js"),
                file(1, "src/main/Main.class"),
                file(1, "src/main/.DS_Store"),
                file(1, "out/production/resources/application.yml"),
                file(1, "out/production/resources/static/v1/index.html"),
                file(1, "app/build/generated/sources/annotations/Gen.java")));

        assertThat(dirs).isEmpty();

    }

    @Test
    @DisplayName("건너뛸 이름과 비슷하기만 한 파일과 폴더는 건너뛰지 않는다")
    void keepsLookAlikeNames() {

        List<RecallDir> dirs = RecallDirs.of(List.of(
                file(1, "src/main/build.gradle"),
                file(2, "src/builder/Maker.java"),
                file(3, ".github/workflows/ci.yml"),
                file(4, "src/main/Main.classic"),
                file(5, "scripts/build"),
                file(6, "ops/out")));

        assertThat(dirs).extracting(RecallDir::path)
                .containsExactlyInAnyOrder("/src/main/", "/src/builder/", "/.github/workflows/", "/scripts/", "/ops/");

    }

    @Test
    @DisplayName("filesChanged 는 그 폴더의 서로 다른 경로 수이고 myCommits 는 그 폴더 파일을 하나라도 바꾼 내 커밋 수다")
    void countsDistinctPathsAndCommits() {

        List<RecallDir> dirs = RecallDirs.of(List.of(
                file(1, "src/api/A.java"),
                file(1, "src/api/B.java"),
                file(2, "src/api/A.java"),
                file(3, "src/api/A.java"),
                file(4, "src/ui/C.java")));

        RecallDir api = dirs.stream().filter(dir -> dir.path().equals("/src/api/")).findFirst().orElseThrow();
        assertThat(api.filesChanged()).isEqualTo(2);
        assertThat(api.myCommits()).isEqualTo(3);

    }

    @Test
    @DisplayName("폴더는 내 커밋 많은 순, 같으면 파일 많은 순, 같으면 경로 사전순으로 다섯 개만 돌려준다")
    void ordersAndLimitsDirs() {

        List<OwnCommitFile> files = new ArrayList<>();
        files.add(file(1, "a/one/F.java"));
        files.add(file(2, "a/two/F.java"));
        files.add(file(2, "a/two/G.java"));
        files.add(file(3, "a/three/F.java"));
        files.add(file(4, "a/four/F.java"));
        files.add(file(5, "a/five/F.java"));
        files.add(file(6, "a/six/F.java"));
        files.add(file(7, "a/most/F.java"));
        files.add(file(8, "a/most/F.java"));

        List<RecallDir> dirs = RecallDirs.of(files);

        assertThat(dirs).extracting(RecallDir::path)
                .containsExactly("/a/most/", "/a/two/", "/a/five/", "/a/four/", "/a/one/");

    }

    @Test
    @DisplayName("files 는 겹치지 않는 파일 이름을 그 파일을 바꾼 내 커밋 많은 순, 같으면 이름순으로 다섯 개만 담는다")
    void listsDistinctFileNamesByCommitsThenName() {

        List<OwnCommitFile> files = new ArrayList<>();
        files.add(file(1, "x/y/Beta.java"));
        files.add(file(2, "x/y/Beta.java"));
        files.add(file(3, "x/y/Beta.java"));
        files.add(file(1, "x/y/Gamma.java"));
        files.add(file(1, "x/y/Alpha.java"));
        files.add(file(1, "x/y/Delta.java"));
        files.add(file(1, "x/y/Echo.java"));
        files.add(file(1, "x/y/Foxtrot.java"));

        List<RecallDir> dirs = RecallDirs.of(files);

        assertThat(dirs).singleElement().satisfies(dir -> {
            assertThat(dir.filesChanged()).isEqualTo(6);
            assertThat(dir.files()).containsExactly("Beta.java", "Alpha.java", "Delta.java", "Echo.java", "Foxtrot.java");
        });

    }

    @Test
    @DisplayName("끝 두 단계가 같은 서로 다른 경로의 같은 이름 파일은 files 에 한 번만 나오고 두 경로의 커밋을 합쳐 센다")
    void mergesSameFileNameFromDifferentPaths() {

        List<RecallDir> dirs = RecallDirs.of(List.of(
                file(1, "front/app/util/Date.js"),
                file(2, "admin/app/util/Date.js"),
                file(3, "front/app/util/Other.js"),
                file(3, "front/app/util/Another.js")));

        assertThat(dirs).singleElement().satisfies(dir -> {
            assertThat(dir.path()).isEqualTo("/app/util/");
            assertThat(dir.filesChanged()).isEqualTo(4);
            assertThat(dir.files()).containsExactly("Date.js", "Another.js", "Other.js");
        });

    }

    @Test
    @DisplayName("경로가 UTF-16 197자를 넘는 폴더는 빼고 197자인 폴더는 남긴다")
    void dropsDirsLongerThan197() {

        String fits = "f".repeat(195);
        String tooLong = "t".repeat(196);

        List<RecallDir> dirs = RecallDirs.of(List.of(file(1, fits + "/A.java"), file(2, tooLong + "/B.java")));

        assertThat(dirs).extracting(RecallDir::path).containsExactly("/" + fits + "/");
        assertThat(dirs.getFirst().path()).hasSize(197);

    }

    @Test
    @DisplayName("폴더마다 정한 질문과 보기 세 개를 그대로 담는다")
    void attachesFixedQuestionAndOptions() {

        List<RecallDir> dirs = RecallDirs.of(List.of(file(1, "a/b/C.java")));

        assertThat(dirs.getFirst().question()).isEqualTo("이 파일들을 만졌을 때, 무엇을 해내려던 중이었나요?");
        assertThat(dirs.getFirst().options())
                .containsExactly("기능을 맡아 만들었어요", "팀원 코드를 이어받았어요", "급하게 버그를 고쳤어요");

    }

    @Test
    @DisplayName("파일이 하나도 없으면 빈 목록이다")
    void returnsEmptyWithoutFiles() {

        assertThat(RecallDirs.of(List.of())).isEmpty();

    }

    private static OwnCommitFile file(long commitId, String path) {

        return new OwnCommitFile(commitId, path);

    }
}
