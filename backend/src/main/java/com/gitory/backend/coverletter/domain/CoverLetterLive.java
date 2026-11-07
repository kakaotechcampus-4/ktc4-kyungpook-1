package com.gitory.backend.coverletter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 한 번 집은 AI 단계의 진행으로, AI 응답을 읽는 스레드가 쓰고 조회 요청이 읽어서 모든 메서드를 잠근다 */
final class CoverLetterLive {

    private final List<AssignStep> assignSteps = new ArrayList<>();
    private final List<DraftStep> draftSteps = new ArrayList<>();
    private final Map<Integer, CoverLetterView.Writing> writing = new HashMap<>();

    synchronized void assignStep(AssignStep step) {

        assignSteps.add(step);

    }

    synchronized void draftStep(DraftStep step) {

        draftSteps.add(step);

    }

    synchronized void write(int questionSeq, int round, String text) {

        writing.put(questionSeq, new CoverLetterView.Writing(text, round));

    }

    // 카드는 문항마다 동시에 골라 단계가 번호 차례로 오지 않으므로, 다 끝난 뒤 저장할 과정과 같은 차례로 늘어놓는다
    synchronized Snapshot snapshot() {

        return new Snapshot(assignSteps.stream().sorted(Comparator.comparingInt(AssignStep::seq)).toList(),
                draftSteps.stream().sorted(Comparator.comparingInt(DraftStep::seq)).toList(), Map.copyOf(writing));

    }

    record Snapshot(List<AssignStep> assignSteps, List<DraftStep> draftSteps,
                    Map<Integer, CoverLetterView.Writing> writing) {
    }
}
