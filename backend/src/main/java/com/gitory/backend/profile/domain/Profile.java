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

import static lombok.AccessLevel.PROTECTED;

/** 사용자 한 명의 인적사항 한 벌로, 화면이 네 목록을 통째로 바꾼다 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "user_profile")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Education> educations;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Career> careers;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<LanguageScore> languages;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Certification> certifications;

    private Instant savedAt;

    static Profile of(Long userId) {

        Profile profile = new Profile();
        profile.userId = userId;
        return profile;

    }

    void replace(List<Education> educations, List<Career> careers, List<LanguageScore> languages,
                 List<Certification> certifications) {

        this.educations = new ArrayList<>(educations);
        this.careers = new ArrayList<>(careers);
        this.languages = new ArrayList<>(languages);
        this.certifications = new ArrayList<>(certifications);
        // DB 가 µs 까지 저장해, 저장 직후 응답과 다시 읽은 값이 같게 자른다
        this.savedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    }

    ProfileView view() {

        return new ProfileView(List.copyOf(educations), List.copyOf(careers), List.copyOf(languages),
                List.copyOf(certifications), savedAt);

    }
}
