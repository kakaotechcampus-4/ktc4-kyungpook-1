-- 알림 종류에 자소서 문항에 쓸 만한 새 카드 안내를 더한다
ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('CARDS_DRAFTED', 'REPO_REFRESHED', 'ANALYSIS_FAILED', 'POSTINGS_MATCHED', 'LETTER_CARD_SUGGESTED'));
