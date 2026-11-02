package com.gitory.backend.coverletter.domain;

public record DraftStep(int seq, String actor, String kind, Integer questionSeq, String code, String value,
                        Integer sentenceSeq, String sentence, String action, String reason) {
}
