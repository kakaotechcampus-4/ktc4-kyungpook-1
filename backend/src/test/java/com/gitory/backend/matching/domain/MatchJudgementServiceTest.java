package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.matching.infra.MatchJudgementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchJudgementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    private final MatchJudgementRepository judgements = mock(MatchJudgementRepository.class);
    private final MatchJudgementPort port = mock(MatchJudgementPort.class);
    private final MatchJudgementService service = new MatchJudgementService(judgements, port,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("카드 40장·조건 30개를 넘으면 나눠 물어 한 번에 보내는 양이 AI 상한을 넘지 않는다")
    void splitsCardsAndConditionsOverTheLimits() {

        List<MatchJudgementRequest> asked = new ArrayList<>();
        when(port.judge(any())).thenAnswer(call -> {
            MatchJudgementRequest request = call.getArgument(0);
            asked.add(request);
            return request.conditions().stream().map(c -> new JudgedCondition(c.conditionId(), List.of())).toList();
        });

        service.judge(posting(), criteria(31), cards(41));

        assertThat(asked).hasSize(4);
        assertThat(asked).allSatisfy(request -> {
            assertThat(request.conditions()).hasSizeLessThanOrEqualTo(30);
            assertThat(request.cards()).hasSizeLessThanOrEqualTo(40);
        });

    }

    @Test
    @DisplayName("AI 에게서 판단을 받지 못하면 아무것도 저장하지 않고 이 공고는 판단 없음으로 돌려준다")
    void returnsNothingAndSavesNothingWhenAiFails() {

        when(port.judge(any())).thenThrow(new AgentUnavailableException("실패", true, false, null));

        assertThat(service.judge(posting(), criteria(2), cards(2))).isEmpty();
        verify(judgements, never()).insertIfAbsent(anyLong(), anyString(), anyLong(), anyBoolean(), any(), any());

    }

    private static JobPosting posting() {

        return JobPosting.discovered("acme", "1", "에이크미", "백엔드 개발자", "https://jobs.example.com/1", null, null,
                NOW);

    }

    private static List<JobPostingCriterion> criteria(int count) {

        return LongStream.rangeClosed(1, count).mapToObj(id -> {
            JobPostingCriterion criterion = JobPostingCriterion.extracted(1L, CriterionType.REQUIRED, (int) id,
                    "조건 " + id, "조건", null, List.of(CardTech.JAVA), null, false);
            ReflectionTestUtils.setField(criterion, "id", id);
            return criterion;
        }).toList();

    }

    private static List<MatchingCard> cards(int count) {

        return LongStream.rangeClosed(1, count)
                .mapToObj(id -> new MatchingCard(id, null, "카드 " + id, null, null, "Java 로 만들었다", null,
                        List.of(CardTech.JAVA)))
                .toList();

    }
}
