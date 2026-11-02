package com.gitory.backend.coverletter.domain;

import com.gitory.backend.agent.port.CoverLetterPostingInput;

import java.util.List;
import java.util.UUID;

public record CoverLetterPosting(UUID id, String company, String title, List<String> responsibilities,
                                 List<Condition> conditions) {

    public record Condition(String type, String text, String status, List<Long> metBy) {
    }

    CoverLetterPostingInput input() {

        return new CoverLetterPostingInput(company, title, responsibilities, conditions.stream()
                .map(condition -> new CoverLetterPostingInput.Condition(condition.type(), condition.text(),
                        condition.status(), condition.metBy()))
                .toList());

    }

    static CoverLetterView.PostingRef refOf(CoverLetter letter) {

        CoverLetterPosting posting = letter.getPosting();
        return posting == null ? null : new CoverLetterView.PostingRef(posting.id(), posting.company(), posting.title());

    }

    static CoverLetterPostingInput inputOf(CoverLetter letter) {

        return letter.getPosting() == null ? null : letter.getPosting().input();

    }
}
