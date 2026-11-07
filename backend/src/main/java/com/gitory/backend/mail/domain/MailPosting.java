package com.gitory.backend.mail.domain;

import java.time.Instant;
import java.util.UUID;

public record MailPosting(UUID postingId, String companyName, String title, int requiredMet, int requiredTotal,
                          Instant expiresAt) {
}
