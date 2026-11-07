package com.gitory.backend.mail.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(applicationYml()))
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class,
                    MailHealthContributorAutoConfiguration.class));

    @Test
    @DisplayName("application.yml 의 메일 설정으로 JavaMailSender 가 뜨고 헬스체크는 메일 서버에 접속하지 않는다")
    void mailSenderIsWiredAndHealthDoesNotCheckSmtp() {

        runner.run(context -> {
            assertThat(context).hasSingleBean(JavaMailSender.class);
            assertThat(context).doesNotHaveBean("mailHealthContributor");
        });

    }

    @Test
    @DisplayName("대조 시험: 헬스체크를 끄는 설정이 없으면 메일 헬스체크가 생긴다")
    void healthCheckAppearsWithoutTheSwitch() {

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class,
                        MailHealthContributorAutoConfiguration.class))
                .withPropertyValues("spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context).hasBean("mailHealthContributor"));

    }

    private static PropertySource<?> applicationYml() {

        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            return loaded.get(0);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }

    }
}
