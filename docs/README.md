# 개발 문서 모음

서비스와 팀 소개는 [프로젝트 README](../README.md), 실행·설계·검증 기록은 아래 문서에서 확인하세요.

## 기획과 설계

| 문서 | 확인할 내용 |
| --- | --- |
| [서비스 기획](overview/service-plan.md) | 문제 한 문장 · 동작 흐름 · 자율성 경계(AI·규칙·사람) · 만들 것/안 만들 것 · 안전 설계 · 성공 지표 |
| [테크스펙](overview/tech-spec.md) | 기술 결정 · 서비스 경계 · 데이터 모델 · 리스크 현황 · 실패 화면 · 트레이드오프 · 테스트 전략 · 9/1 판에서 바뀐 것 |
| [가드레일](GUARDRAILS.md) | AI·Spring 쪽 안전 장치와 확인하는 시험 · 지시 주입 대응 |
| [데모 시나리오](final/demo-scenario.md) | 최종 발표 4분 데모 순서 · 준비물 · 일이 틀어졌을 때 |
| [발표 구성안](final/presentation-outline.md) | 10분 발표 장별 시간 · 화면 · 대본 초안 · 예상 질문 |

## 기록

| 문서 | 확인할 내용 |
| --- | --- |
| [개선 기록](improvement-log.md) | 고친 문제와 나아진 숫자 78건 · 상황 → 원인 → 한 일 → 결과 → 근거 |
| [측정 기록](metrics-log.md) | AI 품질·비용·처리 시간·서버 자원 측정 42건 · 다시 돌릴 수 있는 명령 |
| [기술 결정 기록](tech-decisions.md) | 선택지가 둘 이상이던 결정 111건 · 문제 → 선택지 → 고른 것과 이유 → 근거 |

## 실행과 연동

| 영역 | 문서 | 확인할 내용 |
| --- | --- | --- |
| Frontend | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/README.md) | 로컬 실행, 샘플 데이터 체험, API 연결, 테스트 |
| Backend | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/backend/ARCHITECTURE.md) | 모듈별 책임과 참조 방향 |
| AI | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/ai/README.md) | AI 서버 실행과 수집·분석·인터뷰 API |
| BE–AI | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/BE_AI_INTEGRATION.md) | 환경변수, Swagger, OpenAPI 생성, 연결 확인 |
| 분석 | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/AI_DIFF_STAR_INTEGRATION.md) | 선택 후보의 코드 변경 분석과 STAR 생성 |
| 공유 계약 | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/contracts/README.md) | 파트 간 요청·응답 예시와 검증 범위 |
| 운영 | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/DEPLOYMENT.md) | Docker Compose, HTTPS, 자동 배포, 백업 |
| 문제 해결 | [가이드 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/TROUBLESHOOTING.md) | 원인·해결 방법과 재발 방지 기록 |

> 날짜가 있는 문서는 해당 작업 시점의 기록입니다. 현재 동작과 설정은 최신 코드·환경변수 및 각 파트의 실행 가이드를 함께 확인하세요.

## 현재 서비스 화면과 기능

[프로젝트 README](../README.md#주요-기능과-화면)는 2026-11-09 운영 화면 기준으로 갱신했습니다.
구현 당시의 판단과 검증 기록은 아래에서 확인할 수 있습니다. 각 문서의 과거 미구현 표기는 최신 코드와 함께 확인하세요.

| 주제 | 문서 |
| --- | --- |
| 운영 화면 7장과 캡처 출처 | [README 화면 캡처](images/readme/README.md) |
| 시스템 구성과 전체 AI 파이프라인 | [구성도와 확인한 소스](images/architecture/README.md) |
| 한 문장으로 작업 맡기기 | [홈 한 줄 명령 계약·화면](../frontend/docs/2026-11-05-ASSISTANT.md) |
| 지원할 공고를 보여 주는 홈 | [홈 화면 구현](../frontend/src/features/home/HomePage.tsx) · [공고 조건·카드 연결](../frontend/src/features/home/ApplyPanel.tsx) |
| 문항별 자소서 작성·대화 보강·첨삭 | [자소서 초안 화면](../frontend/docs/2026-10-31-COVER_LETTER_DRAFT.md) |
| 경험 카드를 활동·저장소별로 관리 | [활동과 폴더 보기](../frontend/docs/2026-10-31-ACTIVITIES_AND_FOLDERS.md) |

## 화면과 사용자 흐름

| 주제 | 문서 |
| --- | --- |
| 팔레트 문서 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/MATCHA_THEME.md) |
| 반응형 배치 점검 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/RESPONSIVE_LAYOUT_AUDIT.md) |
| 프론트 연동 검토 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/FE_INTEGRATION_REVIEW_2026-09-26.md) |
| 프론트 안정성 문서 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/FRONTEND_RESILIENCE.md) |
| 모바일 UX 정리 문서 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/MOBILE_UX_CLEANUP.md) |
| 브랜딩 이미지 문서 | [문서 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/BRAND_ASSETS.md) |

<details>
<summary><strong>날짜별 프론트 작업·검증 기록</strong></summary>

기존 프로젝트 README에 있던 작업 기록 링크를 모았습니다.

| 날짜 | 작업과 검증 |
| --- | --- |
| 2026-10-20 | [공통 푸터 작업 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-20-MOBILE_FOOTER.md) |
| 2026-10-20 | [카드 UI 검증 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-20-CARD_REVIEW_FLOW.md) |
| 2026-10-18 | [UX 개선 화면별 변경·계약 보존·검증 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-18-UX_POLISH.md) |
| 2026-10-15 | [#219 검증 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-15-METADATA_219.md) |
| 2026-10-13 | [프론트 UI 점검 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-13-SELECTION_UI_POLISH.md) |
| 2026-10-12 | [프론트 카드 계약 작업 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-12-CARD_CONTRACT_183.md) |
| 2026-10-07 | [프론트 스크롤/단계 검증 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-07-SCROLLBAR_STEP_CONTRAST.md) |
| 2026-10-06 | [프론트 UI 개선·검증 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-06-UI_CONSISTENCY.md) |
| 2026-10-04 | [프론트 CI 수정 문서](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-04-NODE24_CHECKS.md) |
| 2026-10-03 | [프론트 작업·검증 문서](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-03-UX_IMPROVEMENTS.md) |
| 2026-10-03 | [카드 작업 문서](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-03-CARD_WORKFLOW.md) |
| 2026-10-01 | [프론트 작업 문서](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/docs/2026-10-01-UX_PLAN.md) |

</details>
