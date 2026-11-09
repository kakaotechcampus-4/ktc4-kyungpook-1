package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGithubUserId(Long githubUserId);

    // 사용자 데이터는 FK 의 ON DELETE CASCADE 가 지우므로 엔티티를 거치지 않고 행만 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM users WHERE id = :id", nativeQuery = true)
    int deleteRow(@Param("id") Long id);
}
