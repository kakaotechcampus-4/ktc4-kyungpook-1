package com.gitory.backend.recommend.domain;

import java.time.Instant;

public record BoardCommit(String sha, String message, String path, Instant at, boolean included, String url) {
}
