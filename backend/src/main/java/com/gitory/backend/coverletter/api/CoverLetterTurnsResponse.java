package com.gitory.backend.coverletter.api;

import com.gitory.backend.coverletter.domain.CoverLetterTurnView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "자소서 하나의 채우기 대화 전체로, 오래된 말부터 온다")
public record CoverLetterTurnsResponse(List<CoverLetterTurnView> turns) {
}
