package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.GithubConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code github_connection} 접근. consent 모듈 밖에서는 보이지 않는다(ModuleBoundaryTest).
 */
public interface GithubConnectionRepository extends JpaRepository<GithubConnection, Long> {

    /**
     * 활성 연결. {@code uq_connection_active} 가 user 당 최대 하나를 보장하므로
     * 여러 건이 나올 수 없다.
     */
    Optional<GithubConnection> findByUserIdAndRevokedAtIsNull(Long userId);
}
