package com.gitory.backend.ingest.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;
import static lombok.AccessLevel.PROTECTED;

/**
 * 소유권 확인 전용 읽기 뷰라 user_repository 의 컬럼 대부분을 매핑하지 않는다
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
}
