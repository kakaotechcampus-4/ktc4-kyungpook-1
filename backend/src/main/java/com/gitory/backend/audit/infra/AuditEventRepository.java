package com.gitory.backend.audit.infra;

import com.gitory.backend.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
