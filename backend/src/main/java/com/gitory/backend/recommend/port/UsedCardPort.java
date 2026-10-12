package com.gitory.backend.recommend.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** recommend 가 카드 모듈을 알지 못한 채 후보를 카드로 쓴 곳을 얻는 인터페이스로, 구현은 모듈을 엮는 job 이 맡는다 */
public interface UsedCardPort {

    /** 지워지지 않은 카드가 있는 후보만 후보 id 별 카드 공개 id 로 돌려준다 */
    Map<Long, UUID> cardIdsOf(Collection<Long> candidateIds);
}
