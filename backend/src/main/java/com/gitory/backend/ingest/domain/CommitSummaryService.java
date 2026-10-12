package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.CommitSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 다른 모듈이 커밋 id 로 커밋의 sha·메시지·작성 시각을 ingest 표를 직접 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class CommitSummaryService {

    private final CommitSummaryRepository commits;

    public Map<Long, CommitSummary> findByIds(Collection<Long> commitIds) {

        return commits.findByIds(commitIds).stream()
                .collect(Collectors.toMap(CommitSummary::id, Function.identity()));

    }
}
