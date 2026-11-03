package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterPolish(String summary, List<Point> points, CoverLetterDrafts.Draft draft) {

    public record Point(int seq, String kind, String category, String title, String detail,
                        List<Integer> sentenceSeqs) {
    }
}
