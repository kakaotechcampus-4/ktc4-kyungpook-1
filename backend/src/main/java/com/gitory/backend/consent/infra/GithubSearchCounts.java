package com.gitory.backend.consent.infra;

import java.util.Map;

record GithubSearchCounts(Map<String, Integer> authoredPullRequests, Map<String, Integer> reviewedPullRequests) {
}
