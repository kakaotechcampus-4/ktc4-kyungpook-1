package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.CompanySource;
import com.gitory.backend.matching.infra.CompanySourceType;
import com.gitory.backend.matching.infra.MatchingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 화면이 같은 회사의 공고를 묶는 id 와 로고 주소를 회사 설정에서 정한다
 * 로켓펀치처럼 한 출처가 여러 회사의 공고를 주면 출처 키만으로는 회사가 갈리지 않아 받은 회사 이름을 붙이고, 로고는 없다
 */
@Component
@RequiredArgsConstructor
public class CompanyDirectory {

    private final MatchingProperties properties;

    public MatchCompany of(String companyKey, String companyName) {

        CompanySource source = properties.sources().stream()
                .filter(candidate -> candidate.key().equals(companyKey))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return new MatchCompany(companyKey, null);
        }
        if (source.type() == CompanySourceType.ROCKETPUNCH) {
            return new MatchCompany(companyKey + ":" + companyName, null);
        }

        return new MatchCompany(companyKey, source.logo());

    }
}
