package com.gitory.backend.coverletter.domain;

public record MaterialAnswer(MaterialItem item, String text, boolean skipped, boolean reused) {
}
