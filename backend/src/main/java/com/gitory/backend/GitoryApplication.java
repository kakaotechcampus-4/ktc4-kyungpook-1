package com.gitory.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Gitory 백엔드 진입점.
 *
 * <p>레포 분석은 초안 생성까지 2~5분이 걸린다(실측: H_churn 122~139초, STAR 배치 140~315초).
 * 동기 요청으로 처리할 수 없으므로 분석은 전부 Job 으로 돌린다 —
 * {@code POST /api/repositories/{id}/analyze} 는 jobId 만 돌려주고,
 * 프론트는 {@code GET /api/jobs/{jobId}} 를 폴링한다.
 */
@EnableAsync
@SpringBootApplication
public class GitoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitoryApplication.class, args);
    }
}
