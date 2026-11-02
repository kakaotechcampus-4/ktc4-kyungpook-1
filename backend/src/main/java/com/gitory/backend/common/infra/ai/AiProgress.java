package com.gitory.backend.common.infra.ai;

import tools.jackson.databind.JsonNode;

/** liveItem 은 STAR 초안의 칸 글(SLOT)·자소서 초안 글(DRAFT_TEXT)·단계(STEP)·모자란 조건 채우기(GAP_CONDITION) 줄일 때만 AI 가 보낸 item 원본이고, 각 어댑터가 읽는다 */
public record AiProgress(String step, int done, Integer total, AiProgressItem item, JsonNode liveItem) {

    public AiProgress(String step, int done, Integer total) {
        this(step, done, total, null, null);
    }

    public AiProgress(String step, int done, Integer total, AiProgressItem item) {
        this(step, done, total, item, null);
    }
}
