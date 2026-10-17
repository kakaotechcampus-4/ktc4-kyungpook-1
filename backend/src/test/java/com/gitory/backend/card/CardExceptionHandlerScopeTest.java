package com.gitory.backend.card;

import com.gitory.backend.card.api.CardExceptionHandler;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 카드 예외 처리기는 card.api 패키지만 맡으므로, 그 밖의 컨트롤러가 필요해 이 테스트만 card.api 밖에 둔다
class CardExceptionHandlerScopeTest {

    @Test
    @DisplayName("card.api 밖의 컨트롤러에서 난 카드 예외는 카드 예외 처리기가 맡지 않는다")
    void ignoresControllersOutsideCardApi() throws Exception {

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OutsideProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(), new CardExceptionHandler())
                .build();

        mvc.perform(get("/probe"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

    }

    @RestController
    static class OutsideProbeController {

        @GetMapping("/probe")
        void fail() {

            throw new CardNotFoundException();

        }
    }
}
