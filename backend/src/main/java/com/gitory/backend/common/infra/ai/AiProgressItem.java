package com.gitory.backend.common.infra.ai;

public record AiProgressItem(String kind, Integer number, String title, Integer reviews, String sha, Integer files,
                             String excluded, Integer units, Integer experiences, String reason) {
}
