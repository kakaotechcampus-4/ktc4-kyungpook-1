-- 카드 버전마다 출처·확정본·시각을 적는 card_version 표를 만들고, 카드에 기간·가리기 규칙·최근 수정 시각·중복 생성 방지 키를 더한다.
-- 카드 버전 N 은 card_version 의 N 번 행과 version_no = N 인 문장 행이며, 새 버전은 네 칸 문장을 통째로 복사하고 빈 칸은 행이 없다(V1 주석의 'version_no <= N 인 최신 행' 규칙을 대신한다).

CREATE TABLE card_version (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id      BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    -- SMALLINT 끝인 32767 다음 번호는 자바에서 음수로 넘어가므로, 그 번호로는 버전이 저장되지 않게 막는다
    version_no   SMALLINT    NOT NULL CHECK (version_no > 0),
    source       VARCHAR(20) NOT NULL
                 CHECK (source IN ('AI_DRAFT', 'USER_EDIT', 'INTERVIEW', 'MASK', 'RESTORE')),
    -- 확정하며 만든 버전이고, 확정을 풀었다 다시 확정하면 한 카드에 여러 개일 수 있다
    is_confirmed BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 직접 수정 버전은 임시 저장이 덮어쓰므로 마지막으로 저장한 시각이다
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (card_id, version_no)
);

ALTER TABLE card_statement
    ADD CONSTRAINT card_statement_version_fk FOREIGN KEY (card_id, version_no)
        REFERENCES card_version (card_id, version_no) ON DELETE CASCADE;

ALTER TABLE card
    ALTER COLUMN title TYPE VARCHAR(256),
    ADD COLUMN period          VARCHAR(50),
    ADD COLUMN mask_rules      JSONB       NOT NULL DEFAULT '[]',
    ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN idempotency_key TEXT,
    ADD CONSTRAINT card_mask_rules_is_array CHECK (jsonb_typeof(mask_rules) = 'array');

CREATE UNIQUE INDEX uq_card_idempotency ON card (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
