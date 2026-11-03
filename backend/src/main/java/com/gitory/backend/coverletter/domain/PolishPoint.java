package com.gitory.backend.coverletter.domain;

import java.util.List;

public record PolishPoint(int seq, PolishKind kind, PolishCategory category, String title, String detail,
                          List<Integer> sentenceSeqs) {
}
