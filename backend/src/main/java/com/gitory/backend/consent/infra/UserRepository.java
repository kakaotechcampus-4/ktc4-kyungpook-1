package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code users} 접근. consent 모듈 밖에서는 보이지 않는다(ModuleBoundaryTest).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** GitHub 번호가 식별자다 — 사용자명(login)은 바뀌므로 조회 키로 쓰지 않는다. */
    Optional<User> findByGithubUserId(Long githubUserId);
}
