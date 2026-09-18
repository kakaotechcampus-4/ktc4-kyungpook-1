package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGithubUserId(Long githubUserId);
}
