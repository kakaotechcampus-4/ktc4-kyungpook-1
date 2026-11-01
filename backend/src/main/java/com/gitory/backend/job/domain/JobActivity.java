package com.gitory.backend.job.domain;

public record JobActivity(long seq, JobStepKey step, JobActivityKind kind, Integer number, String title,
                          Integer reviews, String sha, Integer files, JobActivityExclusion excluded, Integer units,
                          Integer experiences, String reason) {
}
