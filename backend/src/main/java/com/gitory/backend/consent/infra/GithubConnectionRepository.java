package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.GithubConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface GithubConnectionRepository extends JpaRepository<GithubConnection, Long> {

    Optional<GithubConnection> findByUserIdAndRevokedAtIsNull(Long userId);

    // 로그인과 연결 해제가 겹치면 늦게 끝난 쪽이 행 전체를 다시 써 앞쪽이 바꾼 값을 지우므로, 연결을 고치는 두 곳은 잠그며 읽는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GithubConnection> findWithLockByUserIdAndRevokedAtIsNull(Long userId);
}
