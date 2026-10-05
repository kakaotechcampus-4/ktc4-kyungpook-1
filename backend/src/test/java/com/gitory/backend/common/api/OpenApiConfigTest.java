package com.gitory.backend.common.api;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OpenApiConfig.class);

    @Test
    void metadataIsAbsentUnlessDocsAreExplicitlyEnabled() {
        context.run(application -> assertThat(application).doesNotHaveBean(OpenAPI.class));
        context.withPropertyValues("springdoc.api-docs.enabled=false")
                .run(application -> assertThat(application).doesNotHaveBean(OpenAPI.class));
        context.withPropertyValues("springdoc.api-docs.enabled=true")
                .run(application -> assertThat(application).hasSingleBean(OpenAPI.class));
    }
}
