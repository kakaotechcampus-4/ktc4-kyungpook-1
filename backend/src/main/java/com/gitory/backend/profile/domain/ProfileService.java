package com.gitory.backend.profile.domain;

import com.gitory.backend.profile.infra.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 내 인적사항만 읽고 통째로 바꾸며, 다른 모듈(매칭·자소서)은 {@link #of} 로 읽는다 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profiles;
    private final TransactionTemplate transaction;

    @Transactional(readOnly = true)
    public ProfileView of(Long userId) {

        return profiles.findByUserId(userId).map(Profile::view).orElseGet(ProfileView::empty);

    }

    /**
     * 네 목록을 모두 검사한 뒤 한 번에 바꾸고, 빈 목록 네 개를 보내면 '입력 건너뜀' 으로 저장된다
     * 처음 저장이 동시에 두 번 오면 한쪽이 user_id 유니크에 막히므로, 그쪽은 실패한 트랜잭션 밖에서 이미 생긴 줄을 고쳐 쓴다
     */
    public ProfileView replace(Long userId, ProfileInput input) {

        List<Education> educations = ProfileRules.educations(input.educations());
        List<Career> careers = ProfileRules.careers(input.careers());
        List<LanguageScore> languages = ProfileRules.languages(input.languages());
        List<Certification> certifications = ProfileRules.certifications(input.certifications());
        List<InterestCategory> interests = ProfileRules.interests(input.interests());

        try {
            return transaction.execute(status -> save(userId, educations, careers, languages, certifications, interests));
        } catch (DataIntegrityViolationException firstSaveRace) {
            return transaction.execute(status -> save(userId, educations, careers, languages, certifications, interests));
        }

    }

    private ProfileView save(Long userId, List<Education> educations, List<Career> careers,
                             List<LanguageScore> languages, List<Certification> certifications,
                             List<InterestCategory> interests) {

        Profile profile = profiles.findWithLockByUserId(userId).orElseGet(() -> Profile.of(userId));
        profile.replace(educations, careers, languages, certifications, interests);

        return profiles.saveAndFlush(profile).view();

    }
}
