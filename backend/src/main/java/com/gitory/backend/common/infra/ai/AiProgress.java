package com.gitory.backend.common.infra.ai;

import tools.jackson.databind.JsonNode;

/** starItem 은 STAR 초안의 칸 글(SLOT)·단계(STEP) 줄일 때만 AI 가 보낸 item 원본이고, STAR 어댑터가 읽는다 */
public record AiProgress(String step, int done, Integer total, AiProgressItem item, JsonNode starItem) {

    public AiProgress(String step, int done, Integer total) {
        this(step, done, total, null, null);
    }

    public AiProgress(String step, int done, Integer total, AiProgressItem item) {
        this(step, done, total, item, null);
    }
}
