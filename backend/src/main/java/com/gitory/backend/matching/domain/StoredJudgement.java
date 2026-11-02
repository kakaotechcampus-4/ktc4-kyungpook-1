package com.gitory.backend.matching.domain;

import java.util.List;

public record StoredJudgement(List<JudgedSupport> metBy, boolean complete) {
}
