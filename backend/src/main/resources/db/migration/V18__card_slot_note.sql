-- 카드 버전의 칸마다 AI 가 비운 이유 코드나 확인이 필요한 이유 문장을 남긴다.
-- 빈 칸은 문장 행이 없어 이유를 붙일 곳이 따로 필요하고, 칸 하나에 둘 중 하나만 둔다.

CREATE TABLE card_slot_note (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id       BIGINT      NOT NULL,
    version_no    SMALLINT    NOT NULL,
    star_slot     CHAR(1)     NOT NULL CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    drop_reason   VARCHAR(20) CHECK (drop_reason IN ('NO_EVIDENCE', 'OVERCLAIM', 'UNSOURCED_NUMBER', 'TIMEOUT')),
    review_reason TEXT,
    UNIQUE (card_id, version_no, star_slot),
    CONSTRAINT card_slot_note_one_reason CHECK ((drop_reason IS NULL) <> (review_reason IS NULL)),
    CONSTRAINT card_slot_note_version_fk FOREIGN KEY (card_id, version_no)
        REFERENCES card_version (card_id, version_no) ON DELETE CASCADE
);
