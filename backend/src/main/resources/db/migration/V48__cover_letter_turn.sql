-- 초안 뒤 재료를 채우는 대화는 자소서 하나에 하나라, 문항을 옮겨도 이어지고 말마다 어느 문항 이야기인지 단다.
-- seq 는 자소서 안 순번이라 두 요청이 같은 순번을 쓰면 유니크 제약이 막고, 그 유니크 인덱스로 대화를 순서대로 읽는다.

CREATE TABLE cover_letter_turn (
    id              BIGSERIAL   PRIMARY KEY,
    cover_letter_id BIGINT      NOT NULL REFERENCES cover_letter (id) ON DELETE CASCADE,
    question_seq    INT         NOT NULL,
    seq             INT         NOT NULL,
    role            VARCHAR(10) NOT NULL CHECK (role IN ('AI', 'USER')),
    text            TEXT        NOT NULL,
    item            VARCHAR(20),
    status          VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cover_letter_id, seq)
);
