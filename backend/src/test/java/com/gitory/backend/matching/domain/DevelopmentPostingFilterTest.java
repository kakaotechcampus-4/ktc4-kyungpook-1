package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentPostingFilterTest {

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @DisplayName("한국에서 일하는 개발 직무 공고만 남긴다")
    @CsvSource(delimiter = '|', value = {
            "SEOUL | Software Engineer, Backend - 커뮤니티 | true",
            "Seoul, South Korea | Software Engineer, Compiler (Front-end) | true",
            "Dongtan, Hwaseong, South Korea | Systems Software Engineer, Device Driver | true",
            "Seoul, Korea | Machine Learning Engineer | true",
            "Seoul | Software Engineer, iOS | true",
            "Seoul, Korea | QA Engineer (1-Year Contract) | true",
            "Seoul | 서버 개발자 | true",
            "Seoul | Game Client Programmer | true",
            "Seoul | MLOps Engineer | true",
            "Seoul | SW Engineer, Platform | true",
            "Seoul | Research Scientist, LLMs | true",
            "Seoul | Mobile Engineer | true",
            "Seoul, South Korea | Threat Detection Engineer | true",
            "Seoul, South Korea | AI Research Engineer | true",
            "Seoul | Platform S/W Engineer (5년 이상) | true",
            "Seoul, South Korea | Production Engineer, LLM Serving | true",
            "Menlo Park, California, United States; Seoul, Korea | Software Engineer, Runtime | true",
            "대한민국 | 백엔드 엔지니어 | true",
            "대한민국 | 클라우드 엔지니어 | true",
            "서울특별시 강남구 대치동 | 데이터 엔지니어 - 데이터 플랫폼 | true",
            "경기도 성남시 분당구 판교역로 166 | 프로덕트 엔지니어 - 사내 생산성 플랫폼 | true",
            "대구광역시 수성구 알파시티1로 160 | 프론트엔드 개발자 | true",
            "Bundang | Backend Software Engineer | true",
            "Gwacheon | LINE Pay Server Engineer | true",
            "Taipei | Backend Software Engineer | false",
            "대한민국 | 프론트엔드 엔지니어 | true",
            "대한민국 | 서버 엔지니어 | true",
            "대한민국 | 데이터엔지니어 | true",
            "Seoul, South Korea | Hardware - Design Verification Engineer | false",
            "Seoul, South Korea | Senior Manager, Treasury | false",
            "Seoul, Korea | Marketing Manager - Advertiser & Agency | false",
            "Seoul | Radios Engineer | false",
            "Seoul, South Korea | Design Engineer | false",
            "Seoul, South Korea | 에이크미 이츠 사업개발 및 제휴 팀장 | false",
            "Seoul, South Korea | 설비 신뢰성 엔지니어 리드 | false",
            "일본 도쿄도 | 서버 개발자 | false",
            "대한민국 | 영업 담당자 | false",
            "수원 | [Robot] 자율주행 배송로봇 현장 운영 스탭 - 수원 | false",
            "서울 | [Robot] 구매 & 자재 운영 보조 (2개월 단기 계약직) | false",
            "Seoul | [Robot] QC Engineer - Manufacturing Quality | false",
            "Seoul | [Intern] Robotics Project Management | false",
            "Seoul | [HR Div.] Developer Relations Program 운영/지원 | false",
            "Seoul | Robot Software Engineer | true",
            "Santa Clara, California | Senior Software Engineer, Runtime | false",
            "Menlo Park, California, United States | Software Engineer | false"
    })
    void keepsKoreanDevelopmentPostingsOnly(String location, String title, boolean expected) {

        FetchedPosting posting = new FetchedPosting("1", title, "https://jobs.example.com/1", location, null, null, "");

        assertThat(DevelopmentPostingFilter.accepts(posting)).isEqualTo(expected);

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("제목에 시니어·관리자 표시가 있어도 개발 공고면 저장한다")
    @ValueSource(strings = {"Senior Back-end Engineer", "Sr. Backend Engineer", "Staff Security Engineer",
            "Principal Software Engineer", "Engineering Manager, Backend", "Tech Lead, Android",
            "Director of Software Engineering", "Head of Machine Learning", "시니어 백엔드 개발자", "서버 개발자 (리드)",
            "서버 개발자 (팀장)"})
    void keepsSeniorTitledDevelopmentPostings(String title) {

        assertThat(DevelopmentPostingFilter.accepts(posting("Seoul, South Korea", title))).isTrue();

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("더한 근무지 낱말마다 그 근무지의 개발 공고를 남긴다")
    @ValueSource(strings = {"Pohang, South Korea", "Jeju", "Changwon", "Ulsan, Korea", "Naju", "Goyang-si",
            "경상북도 포항시 남구", "제주특별자치도 제주시", "경상남도 창원시", "울산 남구", "전라남도 나주시", "경기도 고양시 일산동구"})
    void keepsDevelopmentPostingsInAddedPlaces(String location) {

        assertThat(DevelopmentPostingFilter.accepts(posting(location, "Backend Engineer"))).isTrue();

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("더한 직무 낱말마다 그 제목의 공고를 남긴다")
    @ValueSource(strings = {"머신러닝 엔지니어", "Research Scientist - Foundation Models", "Forward Deployed Engineer",
            "Compiler Engineer", "Firmware Engineer", "펌웨어 엔지니어", "Robot Perception Engineer", "Robotics Engineer",
            "2026 하반기 신입사원 채용 - SW Engineering", "2026 하반기 신입사원 채용 - Cloud/Infra Engineering",
            "경력 채용 - AX개발", "경력 채용 - IT-IT개발", "경력 채용 - AI서비스개발-서울(수서)", "경력 채용 - 서울(서초)-앱개발",
            "경력 채용 - 서울(서초)-미들웨어 개발", "경력 채용 - 서울(서초)-풀 스택 개발", "연구원 채용 - 데이터 AX 연구개발-KAIST",
            "연구원 채용 - 코어·서비스 AI 연구개발-POSTECH"})
    void keepsPostingsWithAddedRoleWords(String title) {

        assertThat(DevelopmentPostingFilter.accepts(posting("대한민국", title))).isTrue();

    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("고객 쪽 기술직과 개발이 아닌 '개발' 분야는 여전히 버린다")
    @ValueSource(strings = {"Solutions Architect", "Senior Solutions Architect, Korea", "Cloud Architect",
            "경력 채용 - 부동산개발", "경력 채용 - 서울(서초)-사업개발"})
    void stillDropsCustomerFacingAndNonDevelopmentRoles(String title) {

        assertThat(DevelopmentPostingFilter.accepts(posting("Seoul, South Korea", title))).isFalse();

    }

    private static FetchedPosting posting(String location, String title) {

        return new FetchedPosting("1", title, "https://jobs.example.com/1", location, null, null, "");

    }
}
