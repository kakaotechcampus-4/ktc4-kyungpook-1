# FE 계약 반영과 #57 영향 검토

검토 기준: develop ac90a2a, PR #55/#57/#58/#60, contracts/interview/direct-card.json.

## 이번 작업

| 담당 범위 | 반영 |
|---|---|
| 김태희: 오류 코드 | #56에서 이미 반영된 GITHUB_RATE_LIMITED를 유지. 실제 Mock 충돌 응답을 IDEMPOTENCY_KEY_MISMATCH로 변경. 문서의 제안/미확정 문구 제거. |
| 김태희: 재시도 | FAILED와 부분 완료는 서버 retryable이 true일 때만 허용. 첫 렌더·Job 변경·대기 종료도 같은 판단 사용. 자동 재실행 없음. |
| 김태희: #57 계약 | 공유 직접 작성 사례를 읽어 실제 endpoints 요청과 Mock 응답을 검증. MANUAL(후보)과 DIRECT_CARD(되묻기) 분리 유지. contracts 변경에도 FE CI 실행. |
| 김경무: 화면/지원 | 후보 제외 성공 후에만 선택 해제·실행 취소 표시. 실패 시 선택·직접 추가 입력 유지. 처리 중 중복 조작 차단. 모바일 액션 줄바꿈과 검색 초기화, 사용된 후보 커밋 수정 방지. |
| 김경무: 후보 보드 | Mock 유지. 서버 후보 순서/추천 이유/weak 값을 표시하며 FE에서 점수나 순위를 재계산하지 않음. 미확정 랭킹 규칙과 소요시간 단정 문구 제거. |

## #57 FE 영향

1. JobResponse: 필드명/enum은 현재 FE Job 스키마와 일치. pollAfterMs와 200 + FAILED를 유지한다. result=null도 파싱한다.
2. JobView: 현재 retryable은 GITHUB_UNAVAILABLE/GITHUB_RATE_LIMITED만 true다. INTERNAL_ERROR까지 재시도를 허용하던 FE 분기를 제거했다. retryAfterSec는 현재 null이며 대기 시간은 임의로 만들지 않는다.
3. DB uq_job_active: 사용자별 연결 저장소(user_repository_id) 기준. A/B 사용자 Job을 FE에서 전역 하나로 합치지 않는다. GET active 목록이 구현되면 기존 배열 기반 복구를 연결한다.
4. MeView: 현재 응답은 id/login/avatarUrl/plan/github/stats이며 FE Me와 일치. 내부 domain/api DTO 분리는 JSON이 같으면 FE 변경 없음. 응답 필드 분리는 팀 확정 후 적용한다. 현재 stats는 서버의 placeholder이며 실제 집계 완료로 간주하지 않는다.
5. Node: 팀 표준 .nvmrc=22 유지. Node 22 테스트 통과. Node 24.14.1에서는 기존 save-navigation 6건의 AbortSignal realm 문제가 재현됨(나머지 64건 통과). 멘토 결정대로 전역 polyfill을 넣지 않고 기존 비차단 CI matrix로 관찰한다.
6. #55/#58: AI 내부 source_type/ANSWERED/INSUFFICIENT는 Spring이 변환·저장할 계약이다. 프론트가 /internal/*를 직접 호출하거나 AI 응답을 Card로 가정하지 않는다.
7. #60: 저장 취소·마스킹 이탈·토스트/문구 등 별도 PR의 변경은 이 작업에 가져오지 않았다. errorView는 별도 코드 분기만 수정했으므로 #60 메시지 맵과 병합 시 함께 확인한다.

## 실제 연동 대기와 완료 조건

- develop에서 확인된 Job API는 POST /api/repos/{id}/analyze, GET /api/jobs/{id}. 활성 목록/취소, 후보 랭킹과 카드 생성/인터뷰 Spring 경로는 구현 완료 후 통합 검증한다.
- 랭킹 완료 후 후보 목록의 실제 응답을 CandidateBoard 스키마로 검증하고 EMPTY/부분 결과/제외/커밋 선택/카드 생성의 실서버 흐름을 점검한다.
- 부분 결과 보드의 retryAfterSeconds만으로 재실행을 허용하지 않는다. 원래 Job으로 돌아가 서버 권한·대기 시간을 확인한다. Job 정보가 없는 직접 진입에서는 재시도 가능 여부를 추정하지 않는다.
- 직접 작성 공통 사례는 팀 루트 contracts가 원본이다. 개인 프론트 저장소 배포본은 contracts/에 동일 파일을 복사해 사용한다.

## 재현과 검증

- Node 22: npm run typecheck / npm test / npm run e2e:static.
- /repos/r_auth/candidates?demoScenario=candidate-error: 첫 제외·직접 추가·카드 생성이 각각 한 번 실패한다. 입력과 선택은 유지되고 사용자가 다시 눌렀을 때 성공한다.
- 기존 분석 응답 유실·409·저장·부분 결과 흐름도 E2E로 회귀 확인한다.
- 실행 결과: Node 22.17.0 단위 테스트 70개, 프로덕션 빌드와 정적 E2E 35개 통과. 마지막 모바일 스타일 변경 후 반응형·후보 복구 12개 재검증 통과.
- Node 24.14.1: 64개 통과 / 기존 save-navigation 6개 실패. 실서버 연결 시험이 아니라 소스 계약 대조와 Mock 기반 기능 검증이다.
