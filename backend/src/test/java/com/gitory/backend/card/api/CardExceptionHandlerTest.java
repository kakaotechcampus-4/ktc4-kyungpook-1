package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardConfirmedException;
import com.gitory.backend.card.domain.CardNotConfirmableException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.CardVersionNotFoundException;
import com.gitory.backend.card.domain.InvalidCardInputException;
import com.gitory.backend.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CardExceptionHandlerTest {

    @ParameterizedTest(name = "{0} → {1} {2}")
    @MethodSource("cardFailures")
    @DisplayName("card.api 컨트롤러에서 난 카드 예외는 정해진 HTTP 상태와 오류 코드로 바뀐다")
    void mapsCardExceptions(RuntimeException failure, int httpStatus, String code) throws Exception {

        probe(failure).perform(get("/probe"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value(code));

    }

    @Test
    @DisplayName("입력 규칙 위반은 400 INVALID_REQUEST 에 도메인이 만든 메시지를 그대로 싣는다")
    void invalidInputCarriesDomainMessage() throws Exception {

        probe(new InvalidCardInputException("기간은 50자를 넘을 수 없습니다."))
                .perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("기간은 50자를 넘을 수 없습니다."));

    }

    static Stream<Arguments> cardFailures() {

        return Stream.of(
                arguments(Named.of("카드 없음", new CardNotFoundException()), 404, "NOT_FOUND"),
                arguments(Named.of("버전 없음", new CardVersionNotFoundException()), 404, "NOT_FOUND"),
                arguments(Named.of("확정된 카드", new CardConfirmedException()), 409, "CARD_CONFIRMED"),
                arguments(Named.of("확정할 수 없음", new CardNotConfirmableException()), 422, "NOT_CONFIRMABLE"),
                arguments(Named.of("입력 규칙 위반", new InvalidCardInputException("제목을 입력해 주세요.")), 400,
                        "INVALID_REQUEST"));

    }

    // 전역 처리기를 먼저 등록해, 카드 처리기가 우선순위 설정만으로 먼저 고려되는지 함께 본다
    private static MockMvc probe(RuntimeException failure) {

        return MockMvcBuilders.standaloneSetup(new CardProbeController(failure))
                .setControllerAdvice(new GlobalExceptionHandler(), new CardExceptionHandler())
                .build();

    }

    @RestController
    static class CardProbeController {

        private final RuntimeException failure;

        CardProbeController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/probe")
        void fail() {

            throw failure;

        }
    }
}
