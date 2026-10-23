# 개발 문서 모음

서비스와 팀 소개는 [프로젝트 README](../README.md), 실행·설계·검증 기록은 아래 문서에서 확인하세요.

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
