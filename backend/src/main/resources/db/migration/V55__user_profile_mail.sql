-- 새 공고 알림을 메일로도 받을 주소와 수신 여부이며, 주소는 인증하지 않고 사용자가 적은 그대로 둔다
-- 수신을 끈 사용자도 주소는 남겨 다시 켤 때 바로 쓰게 한다
ALTER TABLE user_profile
    ADD COLUMN email        VARCHAR(254),
    ADD COLUMN mail_opt_in  BOOLEAN NOT NULL DEFAULT false;
