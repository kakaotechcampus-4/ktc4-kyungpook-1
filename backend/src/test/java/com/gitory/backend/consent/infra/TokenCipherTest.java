package com.gitory.backend.consent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토큰이 정말 암호화돼 저장되는지 본다. "암호화한다"는 주석은 두 달 뒤에 없는 규칙이다.
 */
class TokenCipherTest {

    private static final String KEY = "test-encryption-key";
    private static final String SALT = "5c0744940b5c369b"; // 16진수여야 한다
    private static final String TOKEN = "gho_16CharacterAccessTokenExample123456";

    private final TokenCipher cipher = new TokenCipher(KEY, SALT);

    @Test
    @DisplayName("암호화한 토큰을 복호화하면 원래 값이 나온다")
    void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt(TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("암호문에 토큰 평문이 들어 있지 않다")
    void cipherTextDoesNotLeakThePlainToken() {
        assertThat(cipher.encrypt(TOKEN)).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("같은 토큰을 두 번 암호화해도 결과가 다르다 — 암호문 비교로 사용자를 엮을 수 없다")
    void encryptionIsNotDeterministic() {
        assertThat(cipher.encrypt(TOKEN)).isNotEqualTo(cipher.encrypt(TOKEN));
    }

    @Test
    @DisplayName("다른 키로는 복호화되지 않는다")
    void anotherKeyCannotDecrypt() {
        String encrypted = cipher.encrypt(TOKEN);
        TokenCipher other = new TokenCipher("different-key", SALT);

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("키나 솔트가 비면 기동 시점에 실패한다 — 운영 중에 조용히 평문으로 새지 않는다")
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> new TokenCipher("", SALT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(KEY, " "))
                .isInstanceOf(IllegalStateException.class);
    }
}
