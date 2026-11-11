package com.gitory.backend.assistant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.assistant.domain.AssistantRunState;
import com.gitory.backend.assistant.domain.AssistantRunView;
import com.gitory.backend.assistant.domain.RunItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "홈 한 줄 명령 실행 한 건과 지금까지 쌓인 줄")
public record AssistantRunResponse(
        @Schema(format = "uuid") String id,
        AssistantRunState state,
        @Schema(description = "지금까지 실행한 도구 걸음 수") int stepsUsed,
        @Schema(description = "한 실행이 쓸 수 있는 도구 걸음 수 상한") int stepLimit,
        @Schema(description = "도는 동안(RUNNING)에만 다음 조회 간격(밀리초), 아니면 null", example = "1000") Integer pollAfterMs,
        @Schema(description = "FAILED 일 때만 AI_UNAVAILABLE 또는 INTERNAL_ERROR, 아니면 null") String errorCode,
        @Schema(description = "seq 순서의 줄. 같은 걸음은 seq 를 바꾸지 않고 ok 와 결과 칸만 채워 같은 줄로 온다") List<AssistantRunItem> items,
        Instant createdAt,
        Instant updatedAt) {

    // 도는 동안 걸음이 붙는 것을 화면이 늦지 않게 보도록 자주 읽는다
    static final int POLL_AFTER_MS = 1000;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssistantRunItem(
            int seq,
            RunItem.Kind kind,
            @Schema(description = "USER 는 사용자의 말, ASK 와 SAY 는 AI 가 쓴 문장") String text,
            AssistantTool tool,
            @Schema(description = "STEP 만. 없으면 하는 중, true 면 끝, false 면 실패(error)") Boolean ok,
            @Schema(description = "실패한 걸음의 코드(NOT_FOUND·NO_CONFIRMED_CARDS·RATE_LIMITED·NOT_MATCHED·GITHUB_DISCONNECTED·CAP_REACHED·NOT_READY·FAILED)") String error,
            AssistantRunFind find,
            List<AssistantRunPosting> postings,
            Integer totalFound,
            @Schema(description = "MATCH_POSTING·FILL_POSTING_GAPS 가 다룬 공고") AssistantRunPosting posting,
            AssistantRunMatch match,
            Integer questionCount,
            @Schema(format = "uuid") String coverLetterId,
            @Schema(format = "uuid") String repoId,
            String repoName,
            @Schema(format = "uuid") String jobId,
            @Schema(description = "ASK 만. QUESTIONS·CHOICE·FREE") String askKind,
            List<String> options,
            @Schema(format = "uuid", description = "ASK_CARD_GAP 이 질문을 연 카드") String cardId,
            String cardTitle,
            @Schema(format = "uuid", description = "POLISH_COVER_LETTER 가 첨삭한 자소서") String letterId,
            @Schema(description = "FILL_POSTING_GAPS 로 맞은 필수 조건이 늘어난 수") Integer gapsFilled,
            @Schema(description = "ASK_CARD_GAP 이 연 질문. 더 물을 것이 없으면 없다") AssistantRunQuestion question,
            @Schema(description = "POLISH_COVER_LETTER 가 첨삭한 문항(번호 순서)") List<AssistantRunPolished> polished,
            @Schema(description = "POLISH_COVER_LETTER 뒤에도 아직 첨삭하지 않은 문항 수") Integer remainingQuestions,
            @Schema(description = "START_COVER_LETTER 가 자주 나오는 문항 3개로 자소서를 만들었을 때만 true, 아니면 null") Boolean defaultQuestions) {

        static AssistantRunItem from(RunItem item) {

            return new AssistantRunItem(item.seq(), item.kind(), item.text(), item.tool(), item.ok(), item.error(),
                    item.find() == null ? null : new AssistantRunFind(item.find().company(), item.find().keywords()),
                    item.postings() == null ? null : item.postings().stream().map(AssistantRunPosting::from).toList(),
                    item.totalFound(), item.posting() == null ? null : AssistantRunPosting.from(item.posting()),
                    item.match() == null ? null : new AssistantRunMatch(item.match().requiredMet(),
                            item.match().requiredTotal(), item.match().met(), item.match().missing()),
                    item.questionCount(), string(item.coverLetterId()), string(item.repoId()), item.repoName(),
                    string(item.jobId()), item.askKind(), item.options(), string(item.cardId()), item.cardTitle(),
                    string(item.letterId()), item.gapsFilled(),
                    item.question() == null ? null
                            : new AssistantRunQuestion(item.question().field(), item.question().text()),
                    item.polished() == null ? null
                            : item.polished().stream()
                            .map(done -> new AssistantRunPolished(done.seq(), done.points()))
                            .toList(),
                    item.remainingQuestions(), item.defaultQuestions());

        }

        private static String string(UUID id) {

            return id == null ? null : id.toString();

        }
    }

    public record AssistantRunFind(String company, List<String> keywords) {
    }

    public record AssistantRunQuestion(@Schema(description = "S·T·A·R") String field, String text) {
    }

    public record AssistantRunPolished(@Schema(description = "문항 번호") int seq,
                                       @Schema(description = "첨삭 포인트 수") int points) {
    }

    public record AssistantRunPosting(
            @Schema(format = "uuid") String postingId,
            String company,
            String title,
            @Schema(description = "마감까지 남은 날. 마감일이 없으면 null") Integer daysLeft,
            @Schema(description = "이미 맞춰 본 공고만 값이 있다") Integer requiredMet,
            Integer requiredTotal) {

        static AssistantRunPosting from(RunItem.Posting posting) {

            return new AssistantRunPosting(posting.postingId().toString(), posting.company(), posting.title(),
                    posting.daysLeft(), posting.requiredMet(), posting.requiredTotal());

        }
    }

    public record AssistantRunMatch(int requiredMet, int requiredTotal, List<String> met, List<String> missing) {
    }

    static AssistantRunResponse from(AssistantRunView run) {

        return new AssistantRunResponse(run.id().toString(), run.state(), run.stepsUsed(), run.stepLimit(),
                run.state() == AssistantRunState.RUNNING ? POLL_AFTER_MS : null, run.errorCode(),
                run.items().stream().map(AssistantRunItem::from).toList(), run.createdAt(), run.updatedAt());

    }
}
