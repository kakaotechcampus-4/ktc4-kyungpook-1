package com.gitory.backend.common.api;

import java.util.List;

/**
 * 커서 페이지네이션 응답(api-spec.md §0). 오프셋을 쓰지 않는 이유는 목록이
 * 분석 스냅샷에 묶여 있어 페이지 도중 삽입이 생길 수 있기 때문이다.
 *
 * @param nextCursor 다음 페이지가 없으면 null
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }
}
