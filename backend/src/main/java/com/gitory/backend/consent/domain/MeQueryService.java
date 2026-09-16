package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 로그인한 사용자 자신의 정보를 모은다.
 *
 * <p>사용자명은 세션이 아니라 DB 에서 읽는다. 세션은 로그인 시점의 사본이라 GitHub 에서
 * 이름을 바꾼 뒤 재로그인 전까지 옛 이름이 남는다 — {@code users.github_login} 이 단일 출처다.
 * 반대로 아바타는 우리 스키마에 없고 GitHub 표시용 값이라 세션에서 온다.
 */
@Service
public class MeQueryService {

    private final UserRepository users;
    private final GithubConnectionRepository connections;

    public MeQueryService(UserRepository users, GithubConnectionRepository connections) {
        this.users = users;
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public MeView load(Long userId, String avatarUrl) {
        User user = users.findById(userId)
                // 세션은 있는데 행이 없다 = 계정이 실제 삭제됐다. 인증 실패로 다루는 게 맞지만
                // 그 판정은 필터가 하고, 여기서는 사실대로 터뜨린다.
                .orElseThrow(() -> new NoSuchElementException("세션의 사용자를 찾을 수 없다: " + userId));

        MeView.Github github = connections.findByUserIdAndRevokedAtIsNull(userId)
                .map(connection -> new MeView.Github(
                        connection.isActive(),
                        connection.scopeList(),
                        connection.getGrantedAt(),
                        // 수집 이력은 ingest 소유(collection_run)라 consent 가 읽지 않는다.
                        null))
                .orElseGet(MeView.Github::notConnected);

        return new MeView(
                String.valueOf(user.getId()),
                user.getGithubLogin(),
                avatarUrl,
                MeView.Plan.FREE,
                github,
                MeView.Stats.none());
    }
}
