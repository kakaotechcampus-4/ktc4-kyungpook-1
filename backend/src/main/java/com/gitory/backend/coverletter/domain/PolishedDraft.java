package com.gitory.backend.coverletter.domain;

import java.util.List;

public record PolishedDraft(String text, List<DraftSentence> sentences, int charCount, int overBy,
                            List<DraftMark> marks, List<MaterialItem> blanks) {
}
