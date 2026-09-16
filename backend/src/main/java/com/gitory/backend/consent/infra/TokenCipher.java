package com.gitory.backend.consent.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * GitHub 액세스 토큰을 암호화해 저장하고 다시 꺼낸다.
 *
 * <p>스펙이 "장기 토큰을 별도 데이터베이스에 평문 저장하지 않는다"고 못 박았다.
 * {@code github_connection.token_enc} 에 들어가는 값은 전부 이 클래스를 거친다 —
 * 암호화하는 자리가 둘이 되면 한쪽이 평문을 쓰기 시작해도 아무도 모른다.
 *
 * <p>{@link Encryptors#delux} 는 AES 256 GCM + 매번 다른 랜덤 IV 다. 그래서 같은 토큰을
 * 두 번 암호화해도 결과가 다르고, 암호문만 보고 "두 사용자가 같은 토큰을 쓴다"를 알 수 없다.
 * 대신 암호문으로 조회할 수 없다 — 토큰은 항상 user 로 찾아서 복호화한다.
 *
 * <p>키는 코드·설정 파일에 두지 않는다. 배포 환경의 비밀 관리에서 환경변수로 주입한다.
 * 키를 바꾸면 기존 암호문은 전부 복호화 불가가 되므로, 교체하려면 재로그인을 강제해
 * 토큰을 다시 받아야 한다.
 */
@Component
public class TokenCipher {

    private final TextEncryptor encryptor;

    /**
     * @param key  암호화 키. 길이 제한은 없지만 짧으면 그만큼 약하다
     * @param salt <b>16진수 문자열</b>이어야 한다 (예: {@code 5c0744940b5c369b}).
     *             Encryptors 가 Hex 로 디코드하므로 16진수가 아니면 기동 시점에 터진다 —
     *             운영 중에 조용히 실패하지 않는다는 뜻이라 이쪽이 낫다.
     */
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
