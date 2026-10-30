package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterDrafts(List<Draft> drafts, List<Step> steps) {

    public record Draft(int questionId, String text, List<Sentence> sentences, int charCount, int overBy,
                        List<Mark> marks, List<String> blanks, String source) {
    }

    public record Sentence(int seq, String text) {
    }

    public record Mark(int sentenceSeq, String code, String value) {
    }

    public record Step(int seq, String actor, String kind, Integer questionId, String code, String value,
                       Integer sentenceSeq, String action, String reason) {
    }
}
