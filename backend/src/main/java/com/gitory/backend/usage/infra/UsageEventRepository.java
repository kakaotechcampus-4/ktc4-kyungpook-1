package com.gitory.backend.usage.infra;

import com.gitory.backend.usage.domain.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {
}
