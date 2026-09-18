package com.gitory.backend.consent.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 키를 바꾸면 저장된 토큰을 복호화할 수 없으므로 재로그인으로 토큰을 다시 받아야 한다 */
@Component
public class TokenCipher {

    private final TextEncryptor encryptor;

    /** salt 는 16진수 문자열이어야 하며, 아니면 기동 시점에 실패한다 */
    public TokenCipher(@Value("${gitory.consent.token-key}") String key,
                       @Value("${gitory.consent.token-salt}") String salt) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(salt)) {
            throw new IllegalStateException(
                    "토큰 암호화 키·솔트가 비어 있다. gitory.consent.token-key / token-salt 를 주입해야 한다");
        }
        this.encryptor = Encryptors.delux(key, salt);
    }

    public String encrypt(String accessToken) {
        return encryptor.encrypt(accessToken);
    }

    public String decrypt(String tokenEnc) {
        return encryptor.decrypt(tokenEnc);
    }
}
