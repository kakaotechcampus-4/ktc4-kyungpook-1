package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/** 로그인한 사용자 정보를 모으며, 사용자명은 가장 마지막 로그인 값이 남는 DB 에서 읽는다. */
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
                .orElseThrow(() -> new NoSuchElementException("세션의 사용자를 찾을 수 없다: " + userId));

        GithubStatus github = connections.findByUserIdAndRevokedAtIsNull(userId)
                .map(GithubStatus::from)
                .orElseGet(GithubStatus::notConnected);

        return new MeView(
                String.valueOf(user.getId()),
                user.getGithubLogin(),
                avatarUrl,
                Plan.FREE,
                github,
                UserStats.none());
    }
}
