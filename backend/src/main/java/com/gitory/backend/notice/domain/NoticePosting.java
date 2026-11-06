package com.gitory.backend.notice.domain;

import java.time.Instant;
import java.util.UUID;

public record NoticePosting(UUID postingId, String companyName, String title, int requiredMet, int requiredTotal,
                            Instant expiresAt) {
}
