package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

/**
 * 카드 버전 하나로, 새 버전은 이전 버전의 네 칸 문장을 통째로 복사해 만들고 빈 칸은 그 버전에 문장 행이 없다
 * 번호가 1부터 빈틈없이 늘고 카드의 현재 버전이 늘 가장 큰 번호가 되도록 버전은 {@link CardVersionWriter} 로만 만든다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card_version")
public class CardVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    private short versionNo;

    @Enumerated(EnumType.STRING)
    private VersionSource source;

    @Column(name = "is_confirmed")
    private boolean confirmed;

    // 덮어쓴 버전은 마지막으로 저장한 시각이 되며, 화면에 보이는 버전 시각은 이 값이다
    private Instant updatedAt;

    private CardVersion(Long cardId, short versionNo, VersionSource source) {
        this.cardId = cardId;
        this.versionNo = versionNo;
        this.source = source;
        this.confirmed = false;
        this.updatedAt = CardTime.now();
    }

    static CardVersion of(Long cardId, short versionNo, VersionSource source) {

        return new CardVersion(cardId, versionNo, source);

    }

    /** 확정본이 아닌 직접 수정 버전만 임시 저장이 덮어쓸 수 있고, 그 밖의 버전 위에서는 이력이 바뀌지 않게 새 버전을 만들어야 한다 */
    public boolean isOverwritable() {

        return source == VersionSource.USER_EDIT && !confirmed;

    }

    /** 임시 저장이 이 버전의 문장을 덮어쓴 뒤 부른다 */
    public void markSaved() {

        if (!isOverwritable()) {
            throw new IllegalStateException("덮어쓸 수 없는 버전이다: " + source + (confirmed ? " 확정본" : ""));
        }

        this.updatedAt = CardTime.now();

    }

    public void markConfirmed() {

        this.confirmed = true;

    }
}
