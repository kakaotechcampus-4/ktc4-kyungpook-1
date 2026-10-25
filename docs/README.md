# 개발 문서 지도

저장소의 문서를 성격별로 모았습니다. 문서마다 **무엇을 정했는지, 무엇을 알 수 있는지**를 한 줄로 적었습니다.
서비스와 팀 소개는 [프로젝트 README](../README.md), 로컬 실행은 [로컬에서 실행하기](../README.md#로컬에서-실행하기)에 있습니다.

처음이라면 이 순서로 읽습니다.

1. [서비스 기획](overview/service-plan.md) — 무엇을 왜 만드는지
2. [테크스펙](overview/tech-spec.md) — 그걸 어떤 구조로 만들었는지
3. 고칠 파트의 README — 어떻게 띄우고 시험하는지

**작성 당시 기록** 표시는 문서 안 날짜나 내용이 지금 코드와 다르다는 뜻입니다. 지우지 않고 그때 판단의 기록으로 둡니다. 지금 동작은 코드와 위 두 개요 문서를 기준으로 봅니다.

---

## 기획과 설계

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [서비스 기획](overview/service-plan.md) | 문제 한 문장과 근거 · 사람이 서는 곳은 후보 고르기와 카드 확정 2곳 · 만들 것과 안 만들 것 · 가장 겁나는 실패는 그럴듯한 거짓말이라는 안전 설계 · 성공 지표와 지금 값 |
| [테크스펙](overview/tech-spec.md) | `ai` 는 바깥(GitHub·모델), `backend` 는 안(DB·규칙·검증)을 맡는 경계 · 카드를 문장 단위로 저장하는 이유 · 리스크 R-1~R-12 와 실패 화면 E-1~E-10 · 트레이드오프 8개 · 테스트 계층별 규모 · 9/1 판에서 바뀐 것 |
| [백엔드 아키텍처](../backend/ARCHITECTURE.md) | 단일 서비스 안 모듈 구조와 참조 방향 · `ModuleBoundaryTest` 가 강제하는 경계 · 분석을 Job 으로 돌리는 이유 · 근거 필수 규칙과 기여 귀속 · **"아직 정하지 않은 것" 절은 작성 당시 기록**(토큰 암호화 키는 지금 `TOKEN_ENC_KEY` 환경변수로 정해짐) |
| [아키텍처 그림 설명](images/architecture/README.md) | README 의 시스템 구성·AI 파이프라인 그림이 어느 커밋 기준인지(10/19 `264ee671`) · **작성 당시 기록**: 공고 매칭 단계가 그림에 없음 |

## 실행과 연동

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [Frontend README](../frontend/README.md) | 목 API 데모 모드 실행 · 목 시나리오 표(PR 0건 · 후보 0개 · 수집 실패 · 한도) · 백엔드에 붙이는 `.env` 값 · 라우트와 Job 복구 흐름 · 검증 명령. 머리의 날짜별 변경 요약과 "아직 없는 것"(Spring 실연동 검증)은 작성 당시 기록 |
| [AI README](../ai/README.md) | AI 서버 실행과 pytest · Swagger 주소와 운영에서 끄는 법 · 수집 상한(PR 50개 · patch 20,000자)과 부분 수집 표시 · LLM 환경변수. "B 질문 생성은 템플릿" 문장은 10/22 되묻기 LLM 전환 전 기록 |
| [BE–AI 연결](BE_AI_INTEGRATION.md) | Spring 이 부르는 AI 내부 API 목록 · 설정 변수와 응답 대기 제한 · AI 와 백엔드를 따로 띄우는 로컬 실행 · Swagger 주소 · OpenAPI 다시 만들기 · 되묻기 루프 계약(#363) |
| [diff 근거·STAR 초안 연동](AI_DIFF_STAR_INTEGRATION.md) | 초안을 diff 근거와 STAR 두 API 로 나눈 이유 · 토큰이 필요한 쪽만 `consent` 가 부름 · 필드 대응표 · 처리 시간과 상한 · 근거 없는 수치는 지우지 않고 확인 필요로 내림 |
| [E2E README](../e2e/README.md) | 가짜 GitHub·LLM 으로 실제 스택을 띄워 로그인부터 카드 확정까지 돌리는 법 · 함께 확인하는 것(HttpOnly 세션 · 토큰 일치 · `#000` 회귀) · 이 시험이 못 보는 것 |
| 백엔드 README | 작성 중(#441) |
| 협업 규칙(`CONTRIBUTING.md`) | 작성 중(#442) |

## 계약

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [프론트 ↔ Spring 계약](../frontend/docs/BACKEND_CONTRACT.md) | 화면이 기대하는 응답 모양과 공통 규칙(봉투 · 오류 코드 · 멱등키) · 엔드포인트별 요청·응답 · 목 서버와 실서버 차이. 코드 상 단일 출처는 `frontend/src/api/schemas.ts`. 매칭 절 제목의 "서버 미구현"은 10/24 운영 켜기 전 표현 |
| [공유 계약 예시](../contracts/README.md) | 세 파트가 같은 사례를 보는지 시험으로 고정한 JSON 목록과 파일마다 어느 시험이 읽는지 |
| [OpenAPI 스냅샷](openapi/README.md) | `backend.json`·`ai.json` 을 서버 없이 보는 법 · 코드와 다르면 BE·AI 시험이 실패 · 다시 만드는 명령 |

## AI 품질

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [평가 결과표](../ai/evals/RESULTS.md) | **먼저 볼 것.** 평가마다 표본 · 결과 · 바꾼 것 한 줄 · 모델 선택부터 PR 없는 저장소 묶기(같은 PR 커밋을 함께 묶은 비율 81%)까지 |
| [LLM 모델 선택 보고서](../ai/evals/MODEL_SELECTION_REPORT.md) | 모델 6종 비교 · 작업 4종 모두 `gpt-5.6-luna` medium 이 같거나 낫고 비용은 1/7~1/10 · 많이 읽는 작업도 high 가 필요 없다는 결과 |
| [카드 한 장 비용](../ai/evals/REPOSITORY_COST_MEASUREMENT.md) | 후보 1건으로 카드 1장을 끝까지 만들 때의 모델별 비용(Luna 약 8.3원) · 구조화 출력 실패 |
| [답변 충분성 판정 기준](../ai/evals/ANSWER_SUFFICIENCY_CRITERIA.md) | 되묻기 답을 `ANSWERED`·`INSUFFICIENT` 로 가르는 기준 C1~C7 · 슬롯별 최소 요소 · 답 속 지시문은 따르지 않음 |
| [매칭 거리 상한](../ai/evals/JOB_MATCHING_EVALUATION.md) | 카드 문장과 공고 조건 검색의 거리 상한을 0.4 로 정한 근거 |
| [매칭 한국어 평가](../ai/evals/JOB_MATCHING_KOREAN_EVALUATION.md) | 한국어 문장에서 지금 모델이 약하다는 측정(MRR 0.32 대 후보 0.70) · 모델별 맞는 거리 상한 · 교체는 #320 |
| [최신 비교 결과 위치](../ai/evals/results/latest/README.md) | STAR·질문 비교 결과 중 커밋하는 집계 파일과 커밋하지 않는 상세 결과 |

## 안전

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [가드레일](GUARDRAILS.md) | 무엇을 어떤 장치가 막고 그 장치를 어느 시험이 확인하는지(AI 쪽 · Spring 쪽) · 지시 주입 사례 18개 결과 · 아직 없는 장치와 알려진 한계 |

## 운영

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [배포 가이드](DEPLOYMENT.md) | EC2 1대 제약 · Caddy 로 화면과 API 를 같은 주소에 두는 이유 · 처음 띄우기와 기능 스위치 켜는 순서 · self-hosted runner 자동 배포 · CloudWatch 로그 · DB 백업·복구. **2절 상태표는 10/06 기준 작성 당시 기록**(자동 배포 미완으로 적혀 있으나 지금은 6절대로 동작) |

## 화면

| 문서 | 무엇을 정했나 · 알 수 있나 |
| --- | --- |
| [세이지 그린 · 말차 나이트](../frontend/docs/MATCHA_THEME.md) | 라이트·다크 색 역할표와 색 배치 위계 |
| [브랜딩 이미지](../frontend/docs/BRAND_ASSETS.md) | 테마별 로고를 한 컴포넌트로 쓰는 방식 · favicon 흰 테두리 원인과 수정 |
| [반응형 배치 점검](../frontend/docs/RESPONSIVE_LAYOUT_AUDIT.md) | 320~1920px 기준 폭과 화면별 문제·수정 방향(9/26) |
| [모바일 문구·간격 정리](../frontend/docs/MOBILE_UX_CLEANUP.md) | 320~390px 에서 첫 행동이 보이게 화면별로 남긴 정보와 뺀 정보(9/23) |
| [프론트 안정성](../frontend/docs/FRONTEND_RESILIENCE.md) | 저장 직렬화 · 실패 시 입력 유지 · 멱등키 · 조회 실패 화면의 구조(영문, 9/23). 이후 바뀐 부분은 날짜별 기록에 있음 |
| [README 화면 캡처 설명](images/readme/README.md) | README 캡처가 샘플 데모 화면이고 합성하지 않았다는 출처(10/19) |

## 작업 기록

| 문서 | 무엇을 알 수 있나 |
| --- | --- |
| [프론트엔드 트러블슈팅](../frontend/docs/TROUBLESHOOTING.md) | 9/10~10/20 화면 문제마다 원인 · 수정 · 재발 방지. 프론트 문제 기록은 여기에 쌓습니다 |
| [초기 프론트 트러블슈팅](TROUBLESHOOTING.md) | **작성 당시 기록**(9/23 세 건). 이후 기록은 위 프론트엔드 트러블슈팅으로 옮겨 쌓임 |
| [프론트 안정성 구현 계획](frontend-resilience-plan.md) | **작성 당시 기록**(9/23 계획, 영문). 결과는 프론트 안정성 문서 |
| [FE 계약 반영과 #57 영향 검토](../frontend/docs/FE_INTEGRATION_REVIEW_2026-09-26.md) | **작성 당시 기록**(9/26). "배포는 Mock 체험 모드 유지"는 10/05 운영 배포 전 상태 |
| [FE 계약 정합성 계획](superpowers/plans/2026-09-26-fe-contract-readiness.md) | **작성 당시 기록**(9/26). 오류 계약 · Job 재시도 · 공유 계약 대조 할 일 목록 |

<details>
<summary><strong>날짜별 프론트 작업·검증 기록</strong></summary>

| 날짜 | 문서 | 무엇을 정했나 |
| --- | --- | --- |
| 2026-10-24 | [SNS 공유 이미지 라이트 테마](../frontend/docs/2026-10-24-LIGHT_OG.md) | 공유 이미지 색과 로고 |
| 2026-10-24 | [소개 인트로 반복 재생](../frontend/docs/2026-10-24-INTRO_REPLAY.md) | 진입마다 인트로를 재생하고 중앙 구간만 늘림 |
| 2026-10-23 | [레포 선택 E2E 초기화 경합](../frontend/docs/2026-10-23-SELECT_TEST_RACE.md) | 정적 E2E 1건 실패의 원인과 고친 법 |
| 2026-10-23 | [카드 삭제 #351](../frontend/docs/2026-10-23-CARD_DELETE.md) | 카드 삭제 화면과 백엔드 API #369 연결 |
| 2026-10-23 | [로고 인트로·SEO](../frontend/docs/2026-10-23-BRAND_SEO.md) | 인트로 · SNS 메타데이터 · 운영 origin 설정 |
| 2026-10-21 | [매칭 검색 입력 안정화](../frontend/docs/2026-10-21-MATCH_SEARCH.md) | 입력이 이전 값으로 돌아가던 원인과 상태 분리 |
| 2026-10-21 | [프론트 #332–335 후속](../frontend/docs/2026-10-21-FRONTEND_FOLLOWUPS.md) | 작업 목록 정렬 · 후보 접기 · 요청 제한 안내 |
| 2026-10-21 | [카드 분야·기술 태그 #339](../frontend/docs/2026-10-21-CARD_TAGS.md) | AI #337 → BE #338 → FE #339 머지 순서와 표시 규칙 |
| 2026-10-20 | [모바일 공통 푸터](../frontend/docs/2026-10-20-MOBILE_FOOTER.md) | 하단 겹침 5곳 · 버튼 줄바꿈 수정과 기능 점검 |
| 2026-10-20 | [카드 초안 진행·연속 검토](../frontend/docs/2026-10-20-CARD_REVIEW_FLOW.md) | 진행 표시를 카드 수로 · 근거 없는 남은 시간 추정 제거 |
| 2026-10-19 | [공고 조건 매칭 화면](../frontend/docs/2026-10-19-MATCHING.md) | 목록·상세 배치 · 등급은 서버 값 그대로, 합격 가능성으로 말하지 않음 |
| 2026-10-19 | [로그인 예시 여백](../frontend/docs/2026-10-19-LANDING_SPACING.md) | 모바일 예시 위아래 간격 |
| 2026-10-18 | [UX 개선 설계와 실행](../frontend/docs/2026-10-18-UX_POLISH.md) | 화면별 대표 행동 · 입력 보존 · 계약 보존 |
| 2026-10-18 | [백엔드 요청 반영](../frontend/docs/2026-10-18-FE_REQUESTS.md) | `AI_UNAVAILABLE` 안내 · 서버 기준 재시도 |
| 2026-10-15 | [카드 기본 정보 계약 #219](../frontend/docs/2026-10-15-METADATA_219.md) | 제목·기간 수정의 null·400 계약 |
| 2026-10-13 | [후보 선택·정렬](../frontend/docs/2026-10-13-SELECTION_UI_POLISH.md) | 12개 화면 점검 · 체크박스 조작 영역 44px |
| 2026-10-12 | [카드 API 계약 #183](../frontend/docs/2026-10-12-CARD_CONTRACT_183.md) | 직접 작성 멱등키 · STAR 1000자 · null 규칙 |
| 2026-10-07 | [접힌 메뉴 스크롤·대기 단계](../frontend/docs/2026-10-07-SCROLLBAR_STEP_CONTRAST.md) | 접힌 메뉴 넘침 원인 · 대기 표시 대비 |
| 2026-10-06 | [UI 조작·정렬 일관성](../frontend/docs/2026-10-06-UI_CONSISTENCY.md) | 검색·선택 메뉴·글자 굵기 통일 · 사이드바 글자색 |
| 2026-10-04 | [Node 24 Checks 호환성](../frontend/docs/2026-10-04-NODE24_CHECKS.md) | Node 24 에서만 실패한 시험의 원인 · Vitest 버전 고정 |
| 2026-10-03 | [8가지 UX 개선](../frontend/docs/2026-10-03-UX_IMPROVEMENTS.md) | 승인된 진단 8개의 반영 범위 |
| 2026-10-03 | [카드 작업·활용 흐름](../frontend/docs/2026-10-03-CARD_WORKFLOW.md) | 실제 0 과 모름을 구분 · 복사 형식 |
| 2026-10-01 | [UX 정리 계획·판단](../frontend/docs/2026-10-01-UX_PLAN.md) | 자기 확인 체크박스 제거 · 확정 조건 |

</details>

<details>
<summary><strong>프론트 설계안·구현 계획(superpowers)</strong></summary>

설계안(`specs/`)과 그 구현 계획(`plans/`)이 짝입니다. 모두 작업 당시 기록입니다.

| 날짜 | 설계안 | 구현 계획 |
| --- | --- | --- |
| 2026-10-23 | [로고 도킹 인트로](../frontend/docs/superpowers/specs/2026-10-23-logo-dock-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-23-logo-dock.md) |
| 2026-10-13 | [후보 선택과 화면 정렬](../frontend/docs/superpowers/specs/2026-10-13-ui-selection-polish-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-13-ui-selection-polish.md) |
| 2026-10-12 | [#183 카드 API 계약](../frontend/docs/superpowers/specs/2026-10-12-card-contract-183-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-12-card-contract-183.md) |
| 2026-10-07 | [사이드바 글자 대비](../frontend/docs/superpowers/specs/2026-10-07-sidebar-text-contrast-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-07-sidebar-text-contrast.md) |
| 2026-10-07 | [접힌 메뉴 스크롤](../frontend/docs/superpowers/specs/2026-10-07-rail-scrollbar-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-07-rail-scrollbar.md) |
| 2026-10-06 | [UI 조작·정렬 일관성](../frontend/docs/superpowers/specs/2026-10-06-ui-controls-consistency-design.md) | [계획](../frontend/docs/superpowers/plans/2026-10-06-ui-controls-consistency.md) |
| 2026-09-10 | [반응형·UX 개선안](../frontend/docs/superpowers/specs/2026-09-10-gitory-responsive-design.md) | [계획](../frontend/docs/superpowers/plans/2026-09-10-gitory-responsive-ux.md) |
| 2026-09-10 | [조용한 목록형 UI](../frontend/docs/superpowers/specs/2026-09-10-gitory-quiet-ui-design.md) | [계획](../frontend/docs/superpowers/plans/2026-09-10-gitory-quiet-ui.md) |

</details>

---

## 지금 열려 있는 결정

[서비스 기획 §9](overview/service-plan.md#9-지금-열려-있는-결정)와 [테크스펙](overview/tech-spec.md)의 🟡 항목을 옮겼습니다. **원문이 기준**이고, 결정되면 오른쪽 문서에 적은 뒤 이 표와 원문에서 뺍니다.

| 결정할 것 | 이슈 | 결정되면 옮길 문서 |
| --- | --- | --- |
| 자소서 초안을 1차 범위에 넣을지, 넣으면 어디까지 | [#433](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/433) | 서비스 기획 §4 · 테크스펙 §8(트레이드오프 8) |
| 되묻기 최대 질문 수(지금 카드당 3, 멘토 제안 최대 10) | [#431](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/431) | 테크스펙 §11 · [BE–AI 연결](BE_AI_INTEGRATION.md) 되묻기 절 |
| 매칭에서 관심 직무를 어디서 고르게 할지 | [#435](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/435) | 서비스 기획 §3 · [프론트 ↔ Spring 계약](../frontend/docs/BACKEND_CONTRACT.md) 매칭 절 |
| 매칭 임베딩을 한국어 모델로 바꿀지 | [#320](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/320) | 테크스펙 §3 기술 결정 · [평가 결과표](../ai/evals/RESULTS.md) |
| "시간이 줄었다"를 무엇으로 증명할지(기준선) | 없음 | 서비스 기획 §7 · 테크스펙 §10 지표 |
| 모델이 실제로 읽었는지 무엇을 더 남길지(지금은 도구 호출 횟수 `tool_calls_made` 만, R-3) | 없음 | 테크스펙 §6 · [가드레일](GUARDRAILS.md) |
| 평가 판정을 파트 밖 사람이 하게 할지(R-8) | 없음 | 테크스펙 §6 · [평가 결과표](../ai/evals/RESULTS.md) |
| 평가셋 11개의 케이스별 최신 판정 기록 모으기(부풀림 케이스 5 포함) | 없음 | [평가 결과표](../ai/evals/RESULTS.md) |
| 실제 스택 E2E 시나리오 더하기(지금 1개) | [#444](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/444) | [E2E README](../e2e/README.md) |
| 10/27 기능 동결 뒤 범위(실패 화면·문서·발표 준비) | 없음 | 테크스펙 §10 일정 |

---

## 문서 작성 규칙

**어디에 둘지**

| 내용 | 위치 |
| --- | --- |
| 제품 전반(기획 · 테크스펙) | `docs/overview/` |
| 파트 하나의 실행 · 구조 | 그 파트 폴더의 `README.md`·`ARCHITECTURE.md` |
| 두 파트 이상이 걸친 연동 · 안전 · 운영 | `docs/` |
| 파트 간 요청·응답 예시 | `contracts/`(JSON, 시험이 읽음) |
| AI 평가 방법과 결과 | `ai/evals/`(결과는 `RESULTS.md` 에 한 줄 더하기) |
| 날짜별 작업·검증 기록 | 파트의 `docs/` 폴더 |
| 그림 | `docs/images/<용도>/`, 같은 폴더 `README.md` 에 기준 커밋과 출처 |

**파일 이름** — 오래 고쳐 쓰는 문서는 주제 이름(`DEPLOYMENT.md`, `GUARDRAILS.md`), 날짜별 기록은 `YYYY-MM-DD-주제.md` 로 짓습니다.

**새 문서 머리** — 제목 바로 아래에 세 가지를 적습니다.

```markdown
# <제목>

목적: <이 문서가 무엇을 정하는지 한 줄>
작성일: YYYY-MM-DD (고치면 "최종 수정 YYYY-MM-DD")
담당: <이름>
```

**링크**

- 저장소 안 파일은 상대 경로로 겁니다(예: `../ai/evals/RESULTS.md`). `github.com/.../blob/develop/...` 절대 주소는 브랜치가 바뀌면 다른 내용을 가리키므로 쓰지 않습니다. 이슈·PR 은 절대 주소로 겁니다.
- 끊긴 링크를 남기지 않습니다. 파일을 옮기거나 지우면 그 파일을 가리키던 링크를 같이 고칩니다.
- 새 문서를 만들면 이 지도에 한 줄 더합니다. 낡은 문서는 지우지 않고 이 지도에 "작성 당시 기록"으로 표시합니다.

**공개 저장소** — 이 저장소는 public 입니다. 토큰 · 키 · 비밀번호 · 서버 IP 같은 비밀값과 실제 사용자가 쓴 글(답변 · 카드 문장)을 문서에 붙여넣지 않습니다. 예시는 샘플 데이터로 씁니다.
