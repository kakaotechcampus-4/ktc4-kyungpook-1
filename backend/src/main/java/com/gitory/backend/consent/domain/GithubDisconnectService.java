package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.GithubGrantClient;
import com.gitory.backend.consent.infra.TokenCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * GitHub 연결을 해제한다 — 우리 DB 의 토큰을 지우고 GitHub 쪽 앱 권한도 지운다
 * 사용자가 끊어 달라고 한 것이라, GitHub 요청이 실패해도 DB 의 해제는 되돌리지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubDisconnectService {

    private final GithubConnectionRepository connections;
    private final TokenCipher cipher;
    private final GithubGrantClient grants;
    private final TransactionTemplate transaction;

    public void disconnect(Long userId) {

        String token = transaction.execute(status -> revoke(userId));
        if (token == null) {
            return;
        }

        try {
            grants.deleteGrant(token);
        } catch (RuntimeException failed) {
            log.warn("사용자 {} 의 GitHub 앱 권한을 지우지 못했다, 우리 쪽 토큰은 이미 지웠다", userId, failed);
        }

    }

    /** 이미 해제돼 지울 연결이 없거나 토큰을 풀 수 없으면 null 을 돌려준다 */
    private String revoke(Long userId) {

        Optional<GithubConnection> connection = connections.findWithLockByUserIdAndRevokedAtIsNull(userId);
        if (connection.isEmpty()) {
            return null;
        }

        String token = decryptOrNull(connection.get());
        connection.get().revoke();

        return token;

    }

    private String decryptOrNull(GithubConnection connection) {

        if (connection.getTokenEnc() == null) {
            return null;
        }

        try {
            return cipher.decrypt(connection.getTokenEnc());
        } catch (RuntimeException failed) {
            return null;
        }

    }
}
