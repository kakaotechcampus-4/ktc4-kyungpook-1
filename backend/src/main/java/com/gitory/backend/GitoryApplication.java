package com.gitory.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class GitoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitoryApplication.class, args);
    }
}
