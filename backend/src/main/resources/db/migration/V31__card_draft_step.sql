-- 카드 초안 AI 가 거친 단계(첫 초안·검사·자료 열기·고쳐 쓰기)를 순서대로 남겨 카드 화면이 'AI 가 한 일' 로 보여 준다.
-- 칸 메모처럼 AI 초안을 담은 카드 버전에 매달아 카드를 지우면 함께 지워지고, 문구는 화면이 값으로 만든다.

CREATE TABLE card_draft_step (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id    BIGINT      NOT NULL,
    version_no SMALLINT    NOT NULL,
    seq        SMALLINT    NOT NULL,
    round_no   SMALLINT    NOT NULL CHECK (round_no >= 0),
    actor      VARCHAR(5)  NOT NULL CHECK (actor IN ('MODEL', 'CODE')),
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('DRAFT', 'FLAG', 'OPEN', 'RETRY', 'KEEP', 'REWRITE', 'PASS')),
    star_slot  CHAR(1)     CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    code       VARCHAR(40),
    detail     VARCHAR(200),
    material   VARCHAR(12) CHECK (material IN ('ISSUE', 'REVIEW', 'OWNER_REPLY')),
    reason     VARCHAR(60),
    UNIQUE (card_id, version_no, seq),
    CONSTRAINT card_draft_step_version_fk FOREIGN KEY (card_id, version_no)
        REFERENCES card_version (card_id, version_no) ON DELETE CASCADE
);
