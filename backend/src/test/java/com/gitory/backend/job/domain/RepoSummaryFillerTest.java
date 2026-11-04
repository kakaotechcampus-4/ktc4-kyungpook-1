package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.RepoSummaryPort;
import com.gitory.backend.agent.port.RepoSummaryRequest;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ReadmeRefresh;
import com.gitory.backend.ingest.domain.RepositoryReadmeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

class RepoSummaryFillerTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 7L;
    private static final ReadmeRefresh NEEDED = new ReadmeRefresh(true, "grow22", "gitory", "Java", "# gitory");

    private final GithubCollectionAccessPort github = mock(GithubCollectionAccessPort.class);
    private final RepoSummaryPort summaries = mock(RepoSummaryPort.class);
    private final RepositoryReadmeService readmes = mock(RepositoryReadmeService.class);
    private final RepoSummaryFiller filler = new RepoSummaryFiller(github, summaries, readmes);

    @Test
    @DisplayName("AI 요약이 필요하지 않으면 GitHub 도 AI 도 부르지 않는다")
    void doesNothingWhenSummaryIsNotNeeded() {

        filler.fill(USER_ID, CONNECTION_ID, new ReadmeRefresh(false, "grow22", "gitory", "Java", "# gitory"));

        verifyNoInteractions(github, summaries, readmes);

    }

    @Test
    @DisplayName("저장소 정보와 최상위 파일, README 앞부분을 AI 에 보내고 문장이 오면 출처 AI 로 저장한다")
    void savesSentenceFromAi() {

        when(github.topLevelPaths(USER_ID, "grow22", "gitory")).thenReturn(List.of("README.md", "src"));
        when(summaries.summarize(any())).thenReturn(Optional.of("커밋 기록으로 경험 카드를 만들어 주는 서비스예요."));

        filler.fill(USER_ID, CONNECTION_ID, NEEDED);

        ArgumentCaptor<RepoSummaryRequest> sent = ArgumentCaptor.forClass(RepoSummaryRequest.class);
        verify(summaries).summarize(sent.capture());
        assertThat(sent.getValue()).isEqualTo(
                new RepoSummaryRequest(CONNECTION_ID, "grow22", "gitory", "Java", List.of("README.md", "src"), "# gitory"));
        verify(readmes).recordAiSummary(CONNECTION_ID, "커밋 기록으로 경험 카드를 만들어 주는 서비스예요.");

    }

    @Test
    @DisplayName("AI 가 재료가 모자라 문장을 주지 않으면 아무것도 저장하지 않는다")
    void savesNothingWhenAiReturnsNoSentence() {

        when(summaries.summarize(any())).thenReturn(Optional.empty());

        filler.fill(USER_ID, CONNECTION_ID, NEEDED);

        verify(readmes, never()).recordAiSummary(anyLong(), anyString());

    }

    @Test
    @DisplayName("AI 를 부르지 못해도 예외를 던지지 않고 아무것도 저장하지 않는다")
    void swallowsAiFailure() {

        when(summaries.summarize(any())).thenThrow(
                new AgentUnavailableException("AI 저장소 소개 실패", true, false, AiClientException.invalidResponse()));

        assertThatCode(() -> filler.fill(USER_ID, CONNECTION_ID, NEEDED)).doesNotThrowAnyException();

        verify(readmes, never()).recordAiSummary(anyLong(), anyString());

    }

    @Test
    @DisplayName("최상위 파일 읽기가 실패해 빈 목록이 와도 AI 를 부른다")
    void callsAiEvenWithoutTopLevelFiles() {

        when(github.topLevelPaths(USER_ID, "grow22", "gitory")).thenReturn(List.of());
        when(summaries.summarize(any())).thenReturn(Optional.empty());

        filler.fill(USER_ID, CONNECTION_ID, NEEDED);

        ArgumentCaptor<RepoSummaryRequest> sent = ArgumentCaptor.forClass(RepoSummaryRequest.class);
        verify(summaries).summarize(sent.capture());
        assertThat(sent.getValue().topLevelFiles()).isEmpty();

    }

    @Test
    @DisplayName("README 가 없으면 README 앞부분은 null 이 아니라 빈 글로 보낸다")
    void sendsEmptyReadmeHeadWhenReadmeIsMissing() {

        when(summaries.summarize(any())).thenReturn(Optional.empty());

        filler.fill(USER_ID, CONNECTION_ID, new ReadmeRefresh(true, "grow22", "gitory", null, null));

        ArgumentCaptor<RepoSummaryRequest> sent = ArgumentCaptor.forClass(RepoSummaryRequest.class);
        verify(summaries).summarize(sent.capture());
        assertThat(sent.getValue().readmeHead()).isEmpty();
        assertThat(sent.getValue().language()).isNull();

    }
}
