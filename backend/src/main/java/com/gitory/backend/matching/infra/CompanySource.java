package com.gitory.backend.matching.infra;

public record CompanySource(String key, String name, CompanySourceType type, String board, String logo) {

    public CompanySource {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("회사 이름이 비어 있습니다: " + key);
        }
        if (logo != null && !logo.startsWith("https://")) {
            throw new IllegalArgumentException("회사 로고는 https 주소여야 합니다: " + key);
        }
    }
}
