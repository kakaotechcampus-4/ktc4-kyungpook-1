package com.gitory.backend.architecture;

import com.gitory.backend.GitoryApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 모듈 경계를 문서가 아니라 테스트로 강제한다.
 *
 * <p>각 모듈의 {@code package-info.java} 에 적힌 경계 규칙 중 <b>기계로 잴 수 있는 것</b>을
 * 여기 옮겼다. 주석만 있는 경계는 두 달 뒤에 없는 경계다.
 */
class ModuleBoundaryTest {

    private static final String ROOT = "com.gitory.backend";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackagesOf(GitoryApplication.class);

    @Test
    @DisplayName("모듈 간 순환 참조가 없다")
    void noCyclicModuleDependencies() {
        SlicesRuleDefinition.slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("infra 는 같은 모듈 안에서만 접근한다 — 외부 클라이언트가 새어 나가지 않는다")
    void infraIsModulePrivate() {
        // agent 의 LLM 클라이언트와 ingest 의 GitHub 클라이언트가 여기 산다.
        // 다른 모듈은 port 인터페이스로만 이들을 본다. 모델·API 를 바꿀 때
        // 손댈 곳이 한 곳이어야 한다.
        for (String module : Modules.ALL) {
            noClasses()
                    .that().resideOutsideOfPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat()
                    .resideInAPackage(ROOT + "." + module + ".infra..")
                    .as("%s.infra 는 %s 모듈 밖에서 참조할 수 없다".formatted(module, module))
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    @DisplayName("규칙 계층(ingest·recommend)은 agent(LLM)를 참조하지 않는다")
    void ruleLayerDoesNotCallTheModel() {
        // 실측: 팀·개인 판정과 후보 순위는 552토큰 메타데이터만으로 정답이 나왔고
        // 근거값이 전부 정수·문자열이었다. 여기에 LLM 이 끼면 비용·지연만 늘고
        // 재현성이 떨어진다. lowCardWorth 와 기여 집계도 같은 이유로 규칙이다.
        noClasses()
                .that().resideInAnyPackage(ROOT + ".ingest..", ROOT + ".recommend..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".agent..")
                .as("ingest·recommend 는 규칙 계층이다 — 모델을 호출하지 않는다")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("card.domain.evidence 는 agent(LLM)를 참조하지 않는다")
    void evidenceInsideCardDoesNotCallTheModel() {
        // interview·evidence 를 card 로 합치면서 최상위에서 사라진 규칙을 한 칸 내린 것이다.
        //
        // card 는 초안을 만들어야 하니 agent 를 반드시 부른다. 그 옆에 sha 검증 코드가 있으면
        // 근거 sha 가 안 맞을 때 "모델한테 다시 물어보자"가 자연스러운 수정이 되어버린다.
        // 실측에서 유령 sha·오귀속이 나온 경로가 그 모양이다.
        // sha 검증은 입력에 대한 결정적 재조회이고, 모델 판단이 끼면 안 된다.
        noClasses()
                .that().resideInAPackage(ROOT + ".card.domain.evidence..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".agent..")
                .as("근거 검증은 결정적이어야 한다 — 모델에 되묻지 않는다")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("오케스트레이션 의존은 한 방향이다 — 아무도 job 을 참조하지 않는다")
    void nobodyDependsOnJob() {
        // job -> ingest·recommend·card 는 되고, 반대는 안 된다.
        // 반대가 생기면 job 이 순환의 중심이 되고, 분석 흐름을 바꿀 때마다 전 모듈이 흔들린다.
        for (String module : Modules.BELOW_JOB) {
            noClasses()
                    .that().resideInAPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + ".job..")
                    .as("%s 는 job 을 참조할 수 없다 (오케스트레이션은 한 방향)".formatted(module))
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    @DisplayName("도메인은 웹 계층(api)을 참조하지 않는다")
    void domainDoesNotDependOnApi() {
        noClasses()
                .that().resideInAPackage(ROOT + "..domain..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + "..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("컨트롤러는 api 패키지에만 있다")
    void controllersLiveInApiPackage() {
        classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage(ROOT + "..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("JPA 엔티티가 웹 응답으로 새어 나가지 않는다")
    void entitiesAreNotReturnedFromControllers() {
        noClasses()
                .that().resideInAPackage(ROOT + "..api..")
                .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
                .as("컨트롤러·DTO 는 엔티티를 직접 노출하지 않는다 — 응답 봉투는 record 로 만든다")
                .allowEmptyShould(true)
                .check(classes);
    }

    /**
     * 모듈 목록. 새 모듈을 만들면 여기 추가한다 — 추가를 잊으면 경계가 검사되지 않는다.
     *
     * <p>ERD 의 애그리거트와 1:1 이다. 소유 테이블이 없는 모듈은 {@code agent}(경계 모듈)와
     * {@code common} 뿐이다.
     */
    static final class Modules {
        static final String[] ALL = {
                "consent", "ingest", "recommend", "agent", "card", "job", "audit"
        };

        /** {@code job} 을 참조하면 안 되는 모듈들. 오케스트레이션 의존은 한 방향이다. */
        static final String[] BELOW_JOB = {
                "consent", "ingest", "recommend", "card", "audit"
        };

        private Modules() {
        }
    }
}
