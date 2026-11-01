package com.gitory.backend.card.domain;

import java.util.List;

public record MatchingCard(Long id, Long userRepositoryId, String title, String situation, String task, String action,
                           String result, List<CardTech> techs) {
}
