package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.StarFieldState;

import java.util.Map;

public record StarStatesResponse(StarFieldState S, StarFieldState T, StarFieldState A, StarFieldState R) {

    static StarStatesResponse from(Map<StarField, StarFieldState> star) {

        return new StarStatesResponse(
                star.get(StarField.S),
                star.get(StarField.T),
                star.get(StarField.A),
                star.get(StarField.R));

    }
}
