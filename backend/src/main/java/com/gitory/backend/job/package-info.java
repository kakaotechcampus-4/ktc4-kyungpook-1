/**
 * <b>분석 Job 오케스트레이터</b> — 레포 하나의 분석을 비동기로 돌리고 상태를 노출한다.
 *
 * <p>소유 테이블: {@code analysis_job}
 *
 * <h2>왜 최상위 모듈인가</h2>
 * 분석은 {@code ingest → recommend → card} 를 엮는다. 어느 한 모듈에 넣으면 그 모듈이
 * 다른 모듈들을 알아야 해서 의존 방향이 뒤엉킨다. 오케스트레이션은 그 자체로 책임이다.
 *
 * <p><b>의존은 한 방향이다.</b> {@code job → ingest·recommend·card} 는 되고,
 * 반대는 안 된다. 반대가 생기면 job 이 순환의 중심이 된다.
 * {@code ModuleBoundaryTest} 가 이걸 강제한다.
 *
 * <h2>왜 비동기인가</h2>
 * 실측에서 카드 초안 하나에 122~139초, STAR 배치에 140~315초가 걸렸다.
 * 동기 요청으로 처리할 수 없다.
 *
 * <pre>
 * POST /api/repositories/{id}/analyze  →  { jobId }              즉시 반환
 * GET  /api/jobs/{jobId}               →  { state, steps, partial }   폴링
 * </pre>
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>{@code analysis_job.idempotency_key} 가 멱등성을 강제한다.
 *       재시도가 중복 분석·중복 카드를 만들지 않는다(계약 원칙 3).</li>
 *   <li>{@code steps} 는 화면의 4단계 체크리스트와 1:1 대응한다 —
 *       {@code COMMITS → PR_REVIEW → COMPRESS → REASON}.</li>
 *   <li>상한 초과·요청 한도 소진은 실패가 아니라 {@code partial = true} 다.
 *       버리지 않고 사용자에게 표시한다.</li>
 *   <li>{@code error_code} 는 운영용 분류다. 사용자에게 보여 줄 메시지와 분리한다(계약 원칙 4).</li>
 *   <li><b>특정 저장소의 실패가 다른 결과를 오염시키지 않게</b> 작업 단위를 분리한다.</li>
 * </ul>
 *
 * <p>⚠️ {@code PR_REVIEW} 단계의 산출은 <b>후보 추천용이고 카드 초안 입력이 아니다.</b>
 * PR 리뷰 전문을 초안에 넣자 토큰 2.2배·지연 113초를 쓰고 적중률은 오히려 내려갔다.
 */
package com.gitory.backend.job;
