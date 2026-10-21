package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            "대한민국 | 프론트엔드 엔지니어 | true",
            "대한민국 | 서버 엔지니어 | true",
            "대한민국 | 데이터엔지니어 | true",
            "Seoul, South Korea | Hardware - Design Verification Engineer | false",
            "Seoul, South Korea | Senior Manager, Treasury | false",
            "Seoul, Korea | Marketing Manager - Advertiser & Agency | false",
            "Seoul | Radios Engineer | false",
            "Seoul, South Korea | Design Engineer | false",
            "Seoul, South Korea | Research Scientist - Foundation Models | false",
            "Seoul, South Korea | 에이크미 이츠 사업개발 및 제휴 팀장 | false",
            "Seoul, South Korea | 설비 신뢰성 엔지니어 리드 | false",
            "일본 도쿄도 | 서버 개발자 | false",
            "대한민국 | 영업 담당자 | false",
            "Santa Clara, California | Senior Software Engineer, Runtime | false",
            "Menlo Park, California, United States | Software Engineer | false"
    })
    void keepsKoreanDevelopmentPostingsOnly(String location, String title, boolean expected) {

        FetchedPosting posting = new FetchedPosting("1", title, "https://jobs.example.com/1", location, null, null, "");

        assertThat(DevelopmentPostingFilter.accepts(posting)).isEqualTo(expected);

    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("제목에 시니어·관리자 표시가 있는 개발 공고는 남기지 않는다")
    @CsvSource(delimiter = '|', value = {
            "Senior Back-end Engineer | false",
            "Sr. Backend Engineer | false",
            "Staff Security Engineer | false",
            "Principal Software Engineer | false",
            "Engineering Manager, Backend | false",
            "Tech Lead, Android | false",
            "Director of Software Engineering | false",
            "Head of Machine Learning | false",
            "시니어 백엔드 개발자 | false",
            "서버 개발자 (리드) | false",
            "서버 개발자 (팀장) | false",
            "Junior Backend Engineer | true",
            "Backend Engineer | true",
            "신입 서버 개발자 | true",
            "Software Engineer Intern | true",
            "Software Engineer, Leadership Program | true",
            "Staffing Platform Software Engineer | true",
            "그리드 컴퓨팅 개발자 | true"
    })
    void dropsSeniorTitledPostings(String title, boolean expected) {

        FetchedPosting posting = new FetchedPosting("1", title, "https://jobs.example.com/1", "Seoul, South Korea", null, null, "");

        assertThat(DevelopmentPostingFilter.accepts(posting)).isEqualTo(expected);

    }
}
