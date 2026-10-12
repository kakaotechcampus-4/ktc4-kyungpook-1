package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 되묻기 질문 행을 만든다
 * 답이 없는 기본 질문(EVIDENCE_GAP·USER)은 TestFixtures.insertInterviewTurn 이 만들고, 여기에는 그 밖의 모양만 둔다
 */
public final class InterviewFixtures {

    private final JdbcTemplate jdbc;

    public InterviewFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 답이 없는 질문 */
    public Long insertAsked(Long cardId, int seq, String slot, String questionType, String triggerSource,
                            String question) {

        return insertTurn(cardId, seq, slot, questionType, triggerSource, question, null, null, null);

    }

    /** 결과가 ANSWERED 인 질문 */
    public Long insertAnswered(Long cardId, int seq, String slot, String answerText, String answerSource) {

        return insertTurn(cardId, seq, slot, "EVIDENCE_GAP", "USER", "질문", "ANSWERED", answerText, answerSource);

    }

    /** 결과와 답 글을 그대로 넣고, 비워 둘 칸은 null 로 넘긴다 */
    public Long insertTurn(Long cardId, int seq, String slot, String questionType, String triggerSource,
                           String question, String outcome, String answerText, String answerSource) {

        return jdbc.queryForObject(
                "INSERT INTO interview_turn (card_id, seq, star_slot, question_type, trigger_source, question_text, outcome, answer_text, answer_source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, cardId, seq, slot, questionType, triggerSource, question, outcome, answerText,
                answerSource);

    }
}
