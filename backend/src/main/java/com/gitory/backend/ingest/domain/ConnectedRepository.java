package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;
import static lombok.AccessLevel.PROTECTED;

/**
 * user_repository 를 고쳐 쓰지 않고 조회할 때만 쓰는 엔티티라 필요한 컬럼만 매핑한다
 * 나중에 ingest 가 같은 테이블을 쓰는 엔티티를 만들 때 이 엔티티 클래스가 계속 필요한지 다시 볼 것
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "user_repository")
public class ConnectedRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID publicId;

    private Long userId;

    private Long repositoryId;
}
