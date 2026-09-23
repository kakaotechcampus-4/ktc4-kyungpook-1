package com.gitory.backend.job.domain;

import java.util.List;

public record JobResultResponse(String repoId, List<String> cardIds, String verdict, List<String> reasons) {
}
