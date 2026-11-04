package com.gitory.backend.coverletter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonCheckTest {

    @Test
    @DisplayName("공백을 뺀 글자가 20자면 받고 19자면 받지 않으며, 공백은 몇 개를 넣어도 세지 않는다")
    void countsLettersWithoutWhitespace() {

        assertThat(ReasonCheck.problem("가나다라마바사아자차카타파하가나다라마바")).isNull();
        assertThat(ReasonCheck.problem("가나다라마바사아자차카타파하가나다라마")).isNotNull();
        assertThat(ReasonCheck.problem("가 나 다 라 마 바 사 아 자 차 카 타 파 하 가 나 다 라 마 \n\t")).isNotNull();

    }

    @Test
    @DisplayName("같은 글자나 2~3자 조각은 4번 이어져도 받고 5번 이어지면 받지 않는다")
    void rejectsFiveRepeats() {

        assertThat(ReasonCheck.problem("결제 서비스를 만들고 싶어 지원합니다 ㅋㅋㅋㅋ")).isNull();
        assertThat(ReasonCheck.problem("결제 서비스를 만들고 싶어 지원합니다 ㅋㅋㅋㅋㅋ")).isNotNull();
        assertThat(ReasonCheck.problem("결제 서비스를 만들고 싶어 지원합니다 하하하하하하")).isNotNull();
        assertThat(ReasonCheck.problem("결제 서비스를 만들고 싶어 지원합니다 ㅎㅇㅎㅇㅎㅇㅎㅇㅎㅇ")).isNotNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HTTP 통신과 ASP.NET 서버를 다뤄 본 경험을 살려 지원합니다",
            "naver.com 같은 서비스를 쓰며 결제 흐름에 관심이 생겨 지원합니다",
            "결제 흐름을 직접 고쳐 보고 싶습니다… (정말로!) 100% 진심이에요~",
            "매출 10000000원 규모의 결제 흐름을 2024년부터 지켜봐 왔습니다",
            "「결제」 [Spring] + #백엔드 `api` 를 직접 다뤄 보고 싶어서 지원합니다"
    })
    @DisplayName("글 속 http 낱말·도메인 이름·이어진 숫자와 흔한 문장부호는 URL·반복·쓸 수 없는 글자로 보지 않는다")
    void acceptsTechnicalNamesAndCommonPunctuation(String memo) {

        assertThat(ReasonCheck.problem(memo)).isNull();

    }
}
