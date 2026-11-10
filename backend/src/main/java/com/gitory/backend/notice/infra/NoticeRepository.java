package com.gitory.backend.notice.infra;

import com.gitory.backend.notice.domain.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    List<Notice> findTop30ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<Notice> findByPublicIdAndUserId(UUID publicId, Long userId);

    @Modifying
    @Query("UPDATE Notice n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
