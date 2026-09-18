package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.GithubConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GithubConnectionRepository extends JpaRepository<GithubConnection, Long> {

    Optional<GithubConnection> findByUserIdAndRevokedAtIsNull(Long userId);
}
