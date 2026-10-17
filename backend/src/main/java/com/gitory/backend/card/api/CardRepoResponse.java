package com.gitory.backend.card.api;

import com.gitory.backend.ingest.domain.RepositoryReference;
import io.swagger.v3.oas.annotations.media.Schema;

public record CardRepoResponse(
        @Schema(format = "uuid") String id,
        String owner,
        String name) {

    static CardRepoResponse from(RepositoryReference repository) {

        return new CardRepoResponse(repository.publicId().toString(), repository.ownerLogin(), repository.name());

    }
}
