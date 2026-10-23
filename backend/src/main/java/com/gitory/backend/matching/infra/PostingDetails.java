package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.FetchedPosting;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 상세 페이지 하나를 못 읽어도 그 공고만 본문 없이 넘겨, 목록에 있는 공고가 사라짐으로 바뀌지 않게 한다
 * 본문이 빈 공고는 조건 뽑기가 건너뛰므로 이미 뽑은 조건도 그대로 남는다
 */
@Slf4j
final class PostingDetails {

    // 호스트 전체에 걸린 실패라 다음 상세를 보내도 같은 결과이거나 요청을 더 보내면 안 되는 경우다
    private static final Set<FetchFailureCode> COMPANY_WIDE = EnumSet.of(FetchFailureCode.HTTP_429,
            FetchFailureCode.ROBOTS_UNAVAILABLE, FetchFailureCode.CRAWL_DELAY_TOO_LONG, FetchFailureCode.INTERRUPTED);

    private PostingDetails() {
    }

    static FetchedPosting withText(FetchedPosting listed, CompanySource source, Supplier<String> detailText) {

        String text;
        try {
            text = detailText.get();
        } catch (PoliteFetchException e) {
            if (COMPANY_WIDE.contains(e.code())) {
                throw e;
            }
            log.warn("공고 상세를 건너뜀 {} {}: {}", source.name(), listed.externalId(), e.code());
            text = "";
        }
        return new FetchedPosting(listed.externalId(), listed.title(), listed.sourceUrl(), listed.location(),
                listed.sourceUpdatedAt(), listed.expiresAt(), text, listed.imageUrls());

    }
}
