package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantTool;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static lombok.AccessLevel.PROTECTED;

/**
 * 홈 한 줄 명령 실행 한 건과 화면에 보일 줄이다
 * RUNNING 이고 runningSince 가 비어 있으면 줄 서 있고, 차 있으면 워커가 돌리는 중이며 걸음을 저장할 때마다 updatedAt 이 바뀐다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "assistant_run")
public class AssistantRun {

    public static final int MAX_USER_MESSAGES = 10;

    private static final String POSTING_REF_PREFIX = "p";
    private static final Set<AssistantTool> VISIBLE_WORK = EnumSet.of(AssistantTool.START_COVER_LETTER,
            AssistantTool.START_REPO_ANALYSIS, AssistantTool.FILL_POSTING_GAPS, AssistantTool.ASK_CARD_GAP,
            AssistantTool.POLISH_COVER_LETTER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    private AssistantRunState state;

    private int stepLimit;
    private int stepsUsed;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<RepoLabel> repos;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<CardLabel> cards;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<LetterLabel> letters;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<RunItem> items;

    private String errorCode;
    private Instant runningSince;
    private Instant createdAt;
    private Instant updatedAt;

    /** repos·cards·letters 는 이름표(r1…·c1…·l1…)를 붙인 목록으로, 만들 때 정하면 실행이 끝날 때까지 바뀌지 않는다 */
    public static AssistantRun start(Long userId, String text, int stepLimit, List<RepoLabel> repos,
                                     List<CardLabel> cards, List<LetterLabel> letters) {

        AssistantRun run = new AssistantRun();
        Instant now = now();
        run.publicId = UUID.randomUUID();
        run.userId = userId;
        run.state = AssistantRunState.RUNNING;
        run.stepLimit = stepLimit;
        run.repos = new ArrayList<>(repos);
        run.cards = new ArrayList<>(cards);
        run.letters = new ArrayList<>(letters);
        run.items = new ArrayList<>(List.of(RunItem.user(1, text)));
        run.createdAt = now;
        run.updatedAt = now;

        return run;

    }

    public int userMessageCount() {

        return (int) items.stream().filter(item -> item.kind() == RunItem.Kind.USER).count();

    }

    public Optional<RunItem.Posting> postingOf(String ref) {

        return knownPostings().stream().filter(posting -> ref != null && ref.equals(posting.ref())).findFirst();

    }

    public Optional<RepoLabel> repoOf(String ref) {

        return repos.stream().filter(repo -> ref != null && ref.equals(repo.ref())).findFirst();

    }

    // 이름표를 두기 전에 만든 실행은 cards·letters 가 null 이다
    public Optional<CardLabel> cardOf(String ref) {

        return cards == null ? Optional.empty()
                : cards.stream().filter(card -> ref != null && ref.equals(card.ref())).findFirst();

    }

    public Optional<LetterLabel> letterOf(String ref) {

        return letters == null ? Optional.empty()
                : letters.stream().filter(letter -> ref != null && ref.equals(letter.ref())).findFirst();

    }

    /** 사용자에게 보이는 일(자소서·레포 정리·채우기·카드 질문·첨삭)을 시작한 걸음이 성공했으면 true 로, 그 결과로 가는 길이 이미 줄에 남아 있다는 뜻이다 */
    public boolean hasSucceededStart() {

        return items.stream().anyMatch(item -> item.kind() == RunItem.Kind.STEP && Boolean.TRUE.equals(item.ok())
                && VISIBLE_WORK.contains(item.tool()));

    }

    /**
     * 워커가 집은 시각을 남겨, 다른 워커가 같은 실행을 다시 집지 않고 멈춘 실행을 가려낼 수 있게 한다
     * 줄 서서 오래 기다린 실행이 집자마자 멈춘 것으로 보이지 않도록 updatedAt 도 함께 고친다
     */
    public void claim() {

        requireRunning();
        runningSince = now();
        updatedAt = runningSince;

    }

    /** 서버가 다시 뜨면 돌던 실행을 줄 세워, 워커가 이어서 돌리게 한다 */
    public void requeue() {

        runningSince = null;

    }

    /** 물음을 기다리는 실행에만 부르며, 사용자의 말을 더하고 다시 줄 세운다 */
    public void addUserMessage(String text) {

        if (state != AssistantRunState.WAITING_USER || userMessageCount() >= MAX_USER_MESSAGES) {
            throw new IllegalStateException("말을 더할 수 없는 실행이다: " + state);
        }

        items = appended(RunItem.user(nextSeq(), text));
        state = AssistantRunState.RUNNING;
        updatedAt = now();

    }

    /** 걸음을 하는 중으로 먼저 남기고 쓴 걸음 수를 하나 올리며, 돌려주는 값이 걸음의 seq 다 */
    public int startStep(ToolCall call) {

        requireRunning();
        if (stepsUsed >= stepLimit) {
            throw new IllegalStateException("걸음 상한에 닿은 실행이다: " + stepLimit);
        }

        int seq = nextSeq();
        items = appended(RunItem.started(seq, call));
        stepsUsed++;
        updatedAt = now();

        return seq;

    }

    /** 공고는 이 실행에서 처음 나온 순서로 이름표를 붙이고, 이미 이름표가 있는 공고는 같은 이름표를 쓴다 */
    public void finishStep(int seq, ToolResult result) {

        requireRunning();
        List<RunItem> next = new ArrayList<>(items);
        RunItem started = next.get(seq - 1);
        if (started.kind() != RunItem.Kind.STEP) {
            throw new IllegalStateException("걸음이 아닌 줄은 끝낼 수 없다: " + seq);
        }

        next.set(seq - 1, started.finished(result, labeled(result.postings())));
        items = next;
        updatedAt = now();

    }

    /** 서버가 꺼질 때 하던 걸음은 끝났는지 알 수 없어 실패로 닫는다 */
    public void closeInterruptedStep() {

        RunItem last = items.getLast();
        if (last.kind() == RunItem.Kind.STEP && last.ok() == null) {
            finishStep(last.seq(), ToolResult.failed(ToolError.FAILED));
        }

    }

    public void ask(String text, String askKind, List<String> options) {

        requireRunning();
        items = appended(RunItem.ask(nextSeq(), text, askKind, options));
        state = AssistantRunState.WAITING_USER;
        runningSince = null;
        updatedAt = now();

    }

    /** say 가 없으면 끝맺음 말 없이 닫는다 */
    public void finish(String say) {

        requireRunning();
        if (say != null) {
            items = appended(RunItem.say(nextSeq(), say));
        }
        state = AssistantRunState.DONE;
        runningSince = null;
        updatedAt = now();

    }

    public void fail(AssistantRunError error) {

        requireRunning();
        state = AssistantRunState.FAILED;
        errorCode = error.name();
        runningSince = null;
        updatedAt = now();

    }

    private void requireRunning() {

        if (state != AssistantRunState.RUNNING) {
            throw new IllegalStateException("진행 중인 실행에서만 바꿀 수 있다: " + state);
        }

    }

    private int nextSeq() {

        return items.size() + 1;

    }

    private List<RunItem> appended(RunItem item) {

        List<RunItem> next = new ArrayList<>(items);
        next.add(item);

        return next;

    }

    private List<RunItem.Posting> knownPostings() {

        return items.stream()
                .filter(item -> item.kind() == RunItem.Kind.STEP)
                .flatMap(item -> Stream.concat(
                        item.postings() == null ? Stream.<RunItem.Posting>empty() : item.postings().stream(),
                        item.posting() == null ? Stream.<RunItem.Posting>empty() : Stream.of(item.posting())))
                .toList();

    }

    private List<RunItem.Posting> labeled(List<RunItem.Posting> postings) {

        if (postings == null) {
            return null;
        }

        Map<UUID, String> refs = new HashMap<>();
        int last = 0;
        for (RunItem.Posting known : knownPostings()) {
            refs.putIfAbsent(known.postingId(), known.ref());
            last = Math.max(last, Integer.parseInt(known.ref().substring(POSTING_REF_PREFIX.length())));
        }

        List<RunItem.Posting> labeled = new ArrayList<>();
        for (RunItem.Posting posting : postings) {
            String ref = refs.get(posting.postingId());
            if (ref == null) {
                ref = POSTING_REF_PREFIX + ++last;
                refs.put(posting.postingId(), ref);
            }
            labeled.add(posting.withRef(ref));
        }

        return labeled;

    }

    // DB 가 저장하는 µs 까지만 만들어, 저장 직후 엔티티로 만든 응답과 나중에 다시 읽은 값이 같게 한다
    private static Instant now() {

        return Instant.now().truncatedTo(ChronoUnit.MICROS);

    }
}
