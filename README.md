<div align="center">
  <img src="./frontend/public/gitory-mark.png" width="72" alt="Gitory 로고" />

  <h1>Gitory</h1>

  <p><strong>커밋과 PR을 자소서 경험으로</strong></p>
  <p>개발 기록에서 내가 한 일을 찾고, 근거가 연결된 경험 카드로 정리합니다.</p>
  <p>카카오테크 캠퍼스 4기 · 경북대 1팀</p>

  <p>
    <a href="#서비스-소개">서비스 소개</a> ·
    <a href="#서비스-화면">서비스 화면</a> ·
    <a href="#주요-기능">주요 기능</a> ·
    <a href="#서비스-구성">서비스 구성</a> ·
    <a href="#기술-스택">기술 스택</a> ·
    <a href="#팀-소개">팀 소개</a> ·
    <a href="./docs/README.md">개발 문서</a>
  </p>
</div>

---

## 서비스 소개

프로젝트는 끝났는데, 내가 어떤 문제를 풀고 무엇을 배웠는지 설명하기 어려울 때가 있습니다.

**Gitory는 GitHub의 커밋·PR·리뷰를 바탕으로 개발 경험을 정리하는 서비스입니다.**
함께 작업한 기록에서 내 기여를 찾아 경험별로 묶고, 자소서와 면접에 활용할 수 있는 카드로 만듭니다.

| 기록에서 찾고 | 근거로 정리하고 | 내 이야기로 완성합니다 |
| :--- | :--- | :--- |
| 흩어진 커밋과 PR을 경험 단위로 묶습니다. | 코드 변경과 연결된 STAR 초안을 확인합니다. | 부족한 내용은 질문에 답하거나 직접 작성해 보강합니다. |

> **STAR란?** 상황(Situation) · 과제(Task) · 행동(Action) · 결과(Result)를 기준으로 경험을 설명하는 방식입니다.

## 서비스 화면

[샘플 데모](https://gitory-prototypes.vercel.app)에서 캡처한 화면입니다. 표시된 저장소·커밋·카드 내용은 체험용 예시이며, 이미지를 누르면 크게 볼 수 있습니다.

| 01. 저장소 선택 | 02. 경험 후보 확인 |
| :---: | :---: |
| [![공개 저장소 목록에서 정리할 저장소를 선택하는 화면](./docs/images/readme/02-repositories.jpg)](./docs/images/readme/02-repositories.jpg) | [![커밋과 PR을 묶은 경험 후보를 선택하는 화면](./docs/images/readme/03-candidates.jpg)](./docs/images/readme/03-candidates.jpg) |

**03. 근거가 연결된 STAR 카드**

[![상황·과제·행동·결과와 연결된 커밋 근거를 확인하는 경험 카드](./docs/images/readme/04-star-card.jpg)](./docs/images/readme/04-star-card.jpg)

<details>
<summary><strong>경험 정리 홈 · 답변 보강 화면 더 보기</strong></summary>

| 경험 정리 홈 | 질문에 답하며 빈칸 보강 |
| :---: | :---: |
| [![이어서 작성할 카드와 최근 경험을 모아 보는 홈](./docs/images/readme/01-home.jpg)](./docs/images/readme/01-home.jpg) | [![질문과 카드 미리보기를 함께 보며 답변하는 화면](./docs/images/readme/05-interview.jpg)](./docs/images/readme/05-interview.jpg) |

</details>

## 주요 기능

### 01. 개발 기록에서 경험 찾기

GitHub에 로그인하고 공개 저장소를 선택하면 커밋·PR·리뷰 등 개발 활동을 수집합니다.
경험 후보에 연결된 작업을 살펴보고, 정리할 경험을 직접 고를 수 있습니다.

### 02. 근거가 연결된 STAR 초안

선택한 경험의 코드 변경을 분석해 STAR 초안을 만듭니다.
문장에 연결된 커밋 근거를 확인할 수 있고, 근거가 부족한 내용은 빈칸이나 확인이 필요한 항목으로 남깁니다.

### 03. 질문으로 빈칸 채우기

기록만으로 알기 어려운 본인의 역할과 판단, 결과를 질문으로 되짚습니다.
답변을 카드에 반영하고 직접 수정하면서 나의 경험으로 완성합니다.

### 04. 경험 카드 관리와 활용

GitHub 기록이 없는 경험도 직접 작성할 수 있습니다.
임시 저장·수정·확정·버전 관리로 내용을 다듬고, 표시할 정보를 가린 뒤 본문·STAR·근거 포함 형식으로 복사해 활용합니다.

**사용 흐름**

```text
GitHub 연결 → 저장소 선택 → 경험 후보 확인 → STAR 초안 → 질문·직접 수정 → 카드 확정·활용
```

## 서비스 구성

### 시스템 아키텍처

[![단일 EC2의 Docker Compose에서 React·Caddy, Spring Boot, FastAPI, PostgreSQL이 연결되고 GitHub API와 Luna를 호출하는 시스템 아키텍처](./docs/images/architecture/system-architecture.svg)](./docs/images/architecture/system-architecture.svg)

단일 EC2에서 **Caddy가 HTTPS와 React 정적 파일을 제공**하고, `/api` 요청을 Spring Boot로 전달합니다.
백엔드는 인증·분석 작업과 데이터 저장을 관리하며, 내부 네트워크의 FastAPI에 AI 분석을 요청합니다.

### AI 파이프라인

[![GitHub 기록 수집, 커밋 선별, 경험 그룹화, 사용자 선택, diff 분석, STAR 초안 생성, 질문·답변 보강, 카드 확정으로 이어지는 AI 파이프라인](./docs/images/architecture/ai-pipeline.svg)](./docs/images/architecture/ai-pipeline.svg)

**GPT-5.6 Luna**로 경험을 묶고, 사용자가 고른 경험의 코드 변경을 분석해 근거가 연결된 STAR 초안을 만듭니다.
보강 질문은 템플릿 기반이며, 사용자 답변은 백엔드가 원문 그대로 카드의 새 버전으로 저장합니다.

> 그림은 저장소에 정의된 배포 구성과 구현된 처리 흐름 기준입니다. LLM 기반 경험 그룹화·STAR 초안 생성에는 관련 환경변수와 기능 스위치 설정이 필요합니다.

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| Frontend | React 19 · TypeScript · Vite · TanStack Query · React Router · Zod |
| Backend | Java 21 · Spring Boot · Spring Security · Spring Data JPA · Flyway |
| AI | Python 3.11 · FastAPI · Pydantic |
| Database | PostgreSQL 17 |
| Infrastructure | AWS EC2 · Docker Compose · Caddy · GitHub Actions · CloudWatch |
| Test | Vitest · Playwright · JUnit · Testcontainers · pytest |

## 팀 소개

**카카오테크 캠퍼스 4기 경북대 1팀**이 기획부터 개발·배포까지 함께 만드는 프로젝트입니다.

| 이름 | 팀 역할 | 프로젝트 역할 | GitHub |
| --- | --- | --- | --- |
| 곽태훈 | 팀장 | Backend | [@taehun0208](https://github.com/taehun0208) |
| 권혁진 | AI 리드 | AI | [@hyukjin0324](https://github.com/hyukjin0324) |
| 김경무 | 플래너 | Frontend | [@ganggang-0605](https://github.com/ganggang-0605) |
| 김태희 | FE 리드 | Frontend | [@TaeHuiKKIM](https://github.com/TaeHuiKKIM) |
| 장성준 | BE 리드 | Backend | [@Grow22](https://github.com/Grow22) |
| 최민서 | 메이커 | AI | [@chxisoei](https://github.com/chxisoei) |

[프로젝트 기여 내역 보기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/graphs/contributors)

## 개발 문서

| 시작하려는 작업 | 문서 |
| --- | --- |
| 프론트엔드 실행 · 샘플 데이터 체험 | [Frontend README](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/frontend/README.md) |
| 백엔드 구조와 모듈 경계 이해 | [Backend 아키텍처](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/backend/ARCHITECTURE.md) |
| AI 서버 실행과 API 확인 | [AI README](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/ai/README.md) |
| BE–AI 연동 · Swagger · 환경변수 확인 | [BE–AI 연결 및 API 문서](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/BE_AI_INTEGRATION.md) |
| 배포 · 운영 설정 확인 | [배포 가이드](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/blob/develop/docs/DEPLOYMENT.md) |

화면 개선, API 계약, 오류 재현과 검증 기록은 [개발 문서 모음](./docs/README.md)에서 확인할 수 있습니다.
