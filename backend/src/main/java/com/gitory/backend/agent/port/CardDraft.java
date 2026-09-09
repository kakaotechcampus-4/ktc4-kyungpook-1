package com.gitory.backend.agent.port;

import java.util.List;
import java.util.Map;

/**
 * 모델 산출. 저장 전에 {@code card} 모듈이 구조 검증을 한다.
 *
 * @param starText     칸별 문장. 근거가 없으면 그 칸은 아예 없다(null 을 넣지 않는다).
 * @param starEvidence 칸별 근거 sha. 여기 sha 는 {@code evidence} 모듈이 실재를 확인한다.
 * @param sharedWith   이 작업에 다른 사람의 커밋도 섞여 있으면 그 몫. 없으면 null.
 *                     <b>이 필드를 빼면 저자 불일치를 처리할 자리가 없어져
 *                     배제 아니면 흡수, 둘 중 하나로 무너진다.</b>
 * @param removed      자기검증에서 탈락시킨 근거와 이유
 */
public record CardDraft(
        String title,
        Map<String, String> starText,
        Map<String, List<String>> starEvidence,
        String sharedWith,
        List<String> removed
) {
}
