package com.gitory.backend.common.api;

import java.util.List;

/**
 * 커서 기반 페이지 응답. 조회 중에 항목이 추가돼도 페이지가 밀리지 않도록 오프셋 대신 커서를 쓴다.
 *
 * @param nextCursor 다음 페이지가 없으면 null
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }
}
