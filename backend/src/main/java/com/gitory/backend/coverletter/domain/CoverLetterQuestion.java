package com.gitory.backend.coverletter.domain;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static lombok.AccessLevel.PROTECTED;

/**
 * 자소서 문항 하나와 그 배정 카드·질문과 답·초안이다
 * 카드를 지우면 DB 가 카드 칸만 비우고 문항은 남긴다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "cover_letter_question")
public class CoverLetterQuestion {

    private static final String INFERRED = "INFERRED";
    // AI 가 답 하나를 받는 상한과 같아, 덧붙인 답이 다음 초안·첨삭 요청을 실패시키지 않는다
    private static final int ANSWER_MAX_LENGTH = 1_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;
    private Long coverLetterId;
    private short seq;
    private String text;
    private int charLimit;

    @Enumerated(EnumType.STRING)
    private QuestionTheme theme;

    private Long primaryCardId;
    private Long supportingCardId;

    @Enumerated(EnumType.STRING)
    private AssignedBy assignedBy;

    private String pickReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ConsideredCard> considered;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialAsk> asks;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialAnswer> answers;

    private String aiText;
    private String draftText;
    private boolean edited;
    private Integer charCount;
    private Integer overBy;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<DraftSentence> sentences;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<DraftMark> marks;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MaterialItem> blanks;

    @Enumerated(EnumType.STRING)
    private DraftSource draftSource;

    @JdbcTypeCode(SqlTypes.JSON)
    private QuestionPolish polish;

    private int polishAppliedCount;

    public static CoverLetterQuestion of(Long coverLetterId, int seq, String text, int charLimit) {

        CoverLetterQuestion question = new CoverLetterQuestion();
        question.publicId = UUID.randomUUID();
        question.coverLetterId = coverLetterId;
        question.seq = (short) seq;
        question.text = text;
        question.charLimit = charLimit;
        question.considered = new ArrayList<>();
        question.asks = new ArrayList<>();
        question.answers = new ArrayList<>();
        question.sentences = new ArrayList<>();
        question.marks = new ArrayList<>();
        question.blanks = new ArrayList<>();

        return question;

    }

    public void assign(QuestionTheme theme, Long primaryCardId, Long supportingCardId, AssignedBy assignedBy,
                       String pickReason, List<ConsideredCard> considered) {

        this.theme = theme;
        this.primaryCardId = primaryCardId;
        this.supportingCardId = supportingCardId;
        this.assignedBy = assignedBy;
        this.pickReason = pickReason;
        this.considered = new ArrayList<>(considered);

    }

    public void asked(List<MaterialAsk> asks, List<MaterialAnswer> answers) {

        this.asks = new ArrayList<>(asks);
        this.answers = new ArrayList<>(answers);

    }

    public void answer(List<MaterialAnswer> answers) {

        this.answers = new ArrayList<>(answers);

    }

    /** 초안을 쓰기 시작할 때 부르며, 답하지 않은 질문을 넘긴 것으로 남겨 초안과 화면이 같은 답을 보게 한다 */
    public void skipUnanswered() {

        Set<MaterialItem> answered = answers.stream().map(MaterialAnswer::item).collect(Collectors.toSet());
        List<MaterialAnswer> all = new ArrayList<>(answers);
        asks.stream()
                .map(MaterialAsk::item)
                .filter(item -> !answered.contains(item))
                .forEach(item -> all.add(new MaterialAnswer(item, null, true, false)));
        answers = all;

    }

    public void drafted(String aiText, List<DraftSentence> sentences, int charCount, int overBy, List<DraftMark> marks,
                        List<MaterialItem> blanks, DraftSource source) {

        this.aiText = aiText;
        this.draftText = aiText;
        this.edited = false;
        this.charCount = charCount;
        this.overBy = overBy;
        this.sentences = new ArrayList<>(sentences);
        this.marks = new ArrayList<>(marks);
        this.blanks = new ArrayList<>(blanks);
        this.draftSource = source;

    }

    /** 고친 글로 글자 수·넘은 수·남은 빈칸을 다시 세며, 문장 번호와 '확인 필요' 표시는 AI 가 마지막으로 준 글 기준으로 둔다 */
    public void edit(String text) {

        // 첨삭은 그때 글 기준이라 글이 바뀌면 미리 보기를 버린다
        // 두면 적용하기가 직접 고친 글을 옛 글로 만든 첨삭 글로 덮는다
        if (!text.strip().equals(draftText.strip())) {
            polish = null;
        }
        draftText = text;
        edited = !text.equals(aiText);
        charCount = DraftText.countableLength(text);
        overBy = Math.max(0, charCount - charLimit);
        blanks = new ArrayList<>(DraftText.blanksIn(text));

    }

    /** 사용자가 맞다고 확인한 AI 추론 문장의 표시만 지우며, 글은 그대로 둔다 */
    public void confirmInference(int sentenceSeq) {

        List<DraftMark> left = marks.stream()
                .filter(mark -> !(mark.sentenceSeq() == sentenceSeq && INFERRED.equals(mark.code())))
                .toList();
        if (left.size() == marks.size()) {
            throw new CoverLetterStateException("그 문장에는 확인할 AI 추론 표시가 없습니다.");
        }
        marks = new ArrayList<>(left);

    }

    /** 앞 첨삭 결과는 덮으며, 적용하기 전까지 지금 글·문장·표시는 그대로 둔다 */
    public void polished(QuestionPolish polish) {

        this.polish = polish;

    }

    /** 첨삭한 글로 지금 글·문장·표시·빈칸을 바꾸고 첨삭 결과를 비우며, AI 원본은 그대로 둬 원본 보기·되돌리기가 남는다 */
    public void applyPolish() {

        if (polish == null) {
            throw new CoverLetterStateException("적용할 첨삭 결과가 없습니다.");
        }
        replaceDraft(polish.draft());
        polishAppliedCount++;
        polish = null;

    }

    /**
     * 대화에서 받은 답을 같은 항목의 앞 답 뒤에 덧붙여 꼬리 질문의 답이 앞 답을 지우지 않게 하고, 없던 항목이면 뒤에 더한다
     * 질문 단계의 답 저장과 달리 질문에 없던 항목도 받는데, 대화는 초안의 빈 자리를 보고 묻기 때문이다
     * 저장한 답 글을 돌려주며, 덧붙인 글은 1,000자에서 자른다
     */
    public String fillAnswer(MaterialItem item, String text) {

        List<MaterialAnswer> next = new ArrayList<>(answers);
        int at = next.stream().map(MaterialAnswer::item).toList().indexOf(item);
        String before = at < 0 || next.get(at).skipped() ? null : next.get(at).text();
        String joined = before == null || before.isBlank() || before.equals(text) ? text : before + " " + text;
        String saved = joined.length() > ANSWER_MAX_LENGTH ? joined.substring(0, ANSWER_MAX_LENGTH).strip() : joined;
        MaterialAnswer filled = new MaterialAnswer(item, saved, false, false);
        if (at >= 0) {
            next.set(at, filled);
        } else {
            next.add(filled);
        }
        answers = next;

        return saved;

    }

    /** 대화의 답으로 AI 가 다시 쓴 글로 지금 글을 바꾸며, AI 원본은 그대로 두고 옛 글 기준의 첨삭 결과는 비운다 */
    public void refill(PolishedDraft draft) {

        replaceDraft(draft);
        polish = null;

    }

    private void replaceDraft(PolishedDraft draft) {

        draftText = draft.text();
        edited = false;
        charCount = draft.charCount();
        overBy = draft.overBy();
        sentences = new ArrayList<>(draft.sentences());
        marks = new ArrayList<>(draft.marks());
        blanks = new ArrayList<>(draft.blanks());

    }

    public void discardPolish() {

        polish = null;

    }

    /** 사용자가 고른 카드로 바꾸며, AI 가 고른 이유는 더는 이 카드에 맞지 않아 지운다 */
    public void chooseCards(Long primaryCardId, Long supportingCardId) {

        this.primaryCardId = primaryCardId;
        this.supportingCardId = supportingCardId;
        this.assignedBy = AssignedBy.USER;
        this.pickReason = null;

    }
}
