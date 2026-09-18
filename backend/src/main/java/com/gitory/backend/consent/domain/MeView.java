package com.gitory.backend.consent.domain;

public record MeView(
        String id,
        String login,
        String avatarUrl,
        Plan plan,
        GithubStatus github,
        UserStats stats) {

    static MeView of(User user, GithubStatus github, String avatarUrl) {
        return new MeView(String.valueOf(user.getId()), user.getGithubLogin(), avatarUrl,
                Plan.FREE, github, UserStats.none());
    }
}
