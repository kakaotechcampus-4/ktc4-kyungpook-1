package com.gitory.backend.coverletter.domain;

import java.util.List;

public record CoverLetterTalk(List<CoverLetterTurnView> turns, CoverLetterView.Question question,
                              boolean rewriteFailed) {
}
