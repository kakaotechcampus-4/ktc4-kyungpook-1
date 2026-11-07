package com.gitory.backend.profile.domain;

import com.gitory.backend.mail.domain.MailService;
import com.gitory.backend.profile.infra.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 내 인적사항만 읽고 통째로 바꾸며, 다른 모듈(매칭·자소서)은 {@link #of} 로 읽고 메일 수신 정보는 mailAddressOf 와 mailOptInUserIds 로 읽는다 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profiles;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final MailService mails;

    @Transactional(readOnly = true)
    public ProfileView of(Long userId) {

        return profiles.findByUserId(userId).map(Profile::view).orElseGet(ProfileView::empty);

    }

    /** 수신을 켰고 주소가 있을 때만 값이 있다 */
    @Transactional(readOnly = true)
    public Optional<String> mailAddressOf(Long userId) {

        return profiles.findByUserId(userId)
                .filter(Profile::isMailOptIn)
                .map(Profile::getEmail);

    }

    @Transactional(readOnly = true)
    public List<Long> mailOptInUserIds() {

        return profiles.findMailOptInUserIds();

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
        String email = ProfileRules.email(input.email());

        Saved saved;
        try {
            saved = transaction.execute(status -> save(userId, educations, careers, languages, certifications,
                    interests, email, input.mailOptIn()));
        } catch (DataIntegrityViolationException firstSaveRace) {
            saved = transaction.execute(status -> save(userId, educations, careers, languages, certifications,
                    interests, email, input.mailOptIn()));
        }
        // 확인 메일은 커밋이 끝난 뒤에 부탁해, 롤백된 저장이 메일을 보내지 않는다
        if (saved.confirmTo() != null) {
            mails.sendOptInConfirmation(saved.confirmTo());
        }
        return saved.view();

    }

    private Saved save(Long userId, List<Education> educations, List<Career> careers,
                       List<LanguageScore> languages, List<Certification> certifications,
                       List<InterestCategory> interests, String email, Boolean mailOptIn) {

        Profile profile = profiles.findWithLockByUserId(userId).orElseGet(() -> Profile.of(userId));
        boolean wasOptedIn = profile.isMailOptIn();
        String beforeEmail = profile.getEmail();
        profile.replace(educations, careers, languages, certifications, interests);
        profile.changeMail(email, mailOptIn);
        ProfileView saved = profiles.saveAndFlush(profile).view();
        // 매칭이 관심 분야·학력·경력으로 공고를 거르므로 알리고, 롤백된 저장은 듣는 쪽이 받지 않는다
        events.publishEvent(new ProfileChanged(userId));

        boolean confirm = profile.isMailOptIn() && (!wasOptedIn || !Objects.equals(beforeEmail, profile.getEmail()));
        return new Saved(saved, confirm ? profile.getEmail() : null);

    }

    private record Saved(ProfileView view, String confirmTo) {
    }
}
