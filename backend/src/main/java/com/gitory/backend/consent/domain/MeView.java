package com.gitory.backend.consent.domain;

public record MeView(
        String id,
        String login,
        String avatarUrl,
        Plan plan,
        GithubStatus github,
        UserStats stats) {
}
