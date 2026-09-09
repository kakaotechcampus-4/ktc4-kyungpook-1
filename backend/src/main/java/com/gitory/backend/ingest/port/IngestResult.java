package com.gitory.backend.ingest.port;

/**
 * @param partial       상한 초과·요청 한도 소진으로 일부만 모았으면 true.
 *                      실패가 아니라 사용자에게 표시할 사실이다.
 * @param partialReason 운영용 분류. 사용자 메시지와 분리한다.
 */
public record IngestResult(int commits, int pullRequests, int issues,
                           boolean partial, String partialReason) {
}
