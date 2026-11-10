package com.gitory.backend.assistant.domain;

import com.gitory.backend.coverletter.domain.NewQuestion;

import java.util.List;

/** 사용자가 문항을 주지 않았을 때 AI 가 안내하는 자주 나오는 문항 3개라, 글을 바꾸면 AI 쪽 안내 문구와 화면 문구도 맞춘다 */
final class DefaultCoverLetterQuestions {

    static final List<NewQuestion> ALL = List.of(
            new NewQuestion("지원 동기와 입사 후 포부를 적어 주세요", null),
            new NewQuestion("직무와 관련된 기술로 문제를 해결한 경험을 적어 주세요", null),
            new NewQuestion("팀과 협업하며 성과를 낸 경험을 적어 주세요", null));

    private DefaultCoverLetterQuestions() {
    }
}
