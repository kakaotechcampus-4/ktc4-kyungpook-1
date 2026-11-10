package com.gitory.backend.profile.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static lombok.AccessLevel.PROTECTED;

/** 사용자 한 명의 프로필 한 벌로, 관심 분야와 메일 수신 설정을 담는다 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "user_profile")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    // 공고 직무 분류 이름이며, profile 이 matching 을 참조하면 순환이라 이름 글로 둔다
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> interests;

    private String email;

    private boolean mailOptIn;

    private Instant savedAt;

    static Profile of(Long userId) {

        Profile profile = new Profile();
        profile.userId = userId;
        profile.interests = new ArrayList<>();
        return profile;

    }

    /** interests 가 null 이면 저장된 관심 분야를 그대로 둔다 */
    void replace(List<InterestCategory> interests) {

        if (interests != null) {
            this.interests = interests.stream().map(Enum::name).collect(Collectors.toCollection(ArrayList::new));
        }
        // DB 가 µs 까지 저장해, 저장 직후 응답과 다시 읽은 값이 같게 자른다
        this.savedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    }

    /** email 이 null 이면 저장된 주소를, 빈 글이면 주소를 지우고, mailOptIn 이 null 이면 저장된 수신 여부를 그대로 둔다 */
    void changeMail(String email, Boolean mailOptIn) {

        String nextEmail = email == null ? this.email : (email.isEmpty() ? null : email);
        boolean nextOptIn = mailOptIn == null ? this.mailOptIn : mailOptIn;
        if (nextOptIn && nextEmail == null) {
            throw new InvalidProfileInputException("메일 알림을 받으려면 메일 주소를 적어 주세요.");
        }
        this.email = nextEmail;
        this.mailOptIn = nextOptIn;

    }

    ProfileView view() {

        return new ProfileView(interests.stream().map(InterestCategory::valueOf).toList(), email, mailOptIn, savedAt);

    }
}
