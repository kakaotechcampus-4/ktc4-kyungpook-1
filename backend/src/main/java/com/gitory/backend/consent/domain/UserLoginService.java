package com.gitory.backend.consent.domain;

import com.gitory.backend.consent.infra.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 로그인 세션이 없는 워커가 AI 에 보낼 사용자명을 DB 에서 읽는다
 * 내 정보 조회(MeQueryService)와 떼어 두어, 이름만 필요한 곳이 내 정보 숫자를 세는 부품까지 끌어오지 않게 한다
 */
@Service
@RequiredArgsConstructor
public class UserLoginService {

    private final UserRepository users;

    @Transactional(readOnly = true)
    public String loginOf(Long userId) {

        return users.findById(userId)
                .map(User::getGithubLogin)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없다: " + userId));

    }

}
