package com.gitory.backend.consent.infra;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

record GithubCountRun(String token, String viewerId, GithubSearchCounts searches, Instant deadline,
                      AtomicReference<GithubCountStop> stopped, AtomicInteger skipped) {
}
