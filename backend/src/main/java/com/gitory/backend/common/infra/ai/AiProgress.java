package com.gitory.backend.common.infra.ai;

public record AiProgress(String step, int done, Integer total, AiProgressItem item) {

    public AiProgress(String step, int done, Integer total) {
        this(step, done, total, null);
    }
}
