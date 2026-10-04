package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 모듈이 GitHub 을 부를 때 쓸 토큰을 철회하지 않은 연결에서만 복호화해 꺼내 준다
 * 꺼낸 토큰은 GitHub 호출에만 쓰고 로그·DB·응답에 남기지 않는다
 */
@Service
@RequiredArgsConstructor
public class GithubTokenService {

    private final GithubConnectionRepository connections;
    private final TokenCipher tokenCipher;

    @Transactional(readOnly = true)
    public String activeTokenOf(Long userId) {

        GithubConnection connection = connections.findByUserIdAndRevokedAtIsNull(userId)
                .orElseThrow(GithubNotConnectedException::new);

        return tokenCipher.decrypt(connection.getTokenEnc());
    }
}
