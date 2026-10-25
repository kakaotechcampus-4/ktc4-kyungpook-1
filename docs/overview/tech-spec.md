# Gitory 테크스펙

최종 수정 2026-10-25 · 작성 곽태훈(PM) · 원본: 2단계 제출용 테크스펙(9/1) — 9/1 판에서 바뀐 것은 맨 아래 §11
표기 — ✅ 동작함 / 🟡 정할 것 / ⚠️ 아직 못 잼 · 문제·타겟·자율성 경계는 [서비스 기획](service-plan.md)

이 문서는 **결정과 그 이유**만 적습니다. API 모양의 정본은 [OpenAPI 스냅샷](../openapi/)과 [백엔드 계약](../../frontend/docs/BACKEND_CONTRACT.md), 파트 간 예시는 [contracts](../../contracts/README.md), 가드레일 전체는 [GUARDRAILS](../GUARDRAILS.md) 입니다.

---

## 1. 목표

> 자소서에 쓸 경험을 정리하려는 취준생은, 저장소 하나만 고르면 AI 가 커밋·PR·리뷰를 대신 읽어 **근거 커밋 링크가 붙은 카드 후보**를 줄 때, 기억을 처음부터 되짚는 것보다 **짧은 시간에 여러 장을 확정**한다.

1. 저장소당 확정 카드 평균 2장 이상
2. 근거 링크 0개인 카드가 화면에 한 건도 뜨지 않는다
3. 기여를 부풀리지 않는다 — 내 커밋 3개 / 팀 180개 저장소에서 "주도·전담·리드" 0회

## 2. 담당

FE 2 · BE 2 · AI 2. 리드는 그 파트를 혼자 개발하지 않고 기술 결정과 통합을 책임집니다.

| 이름 | 역할 | 주담당 |
| --- | --- | --- |
| 곽태훈 | PM · BE | 범위·최종 결정 · 근거 검사 · 후보 점수 규칙 · 매칭 등급 |
| 장성준 | BE 리드 | 데이터 모델·마이그레이션 · 수집 저장 · Job · 배포 |
| 권혁진 | AI 리드 | 서비스 간 계약 · GitHub 수집 · 초안·되묻기 |
| 최민서 | AI | 평가셋 작성·실행 · 프롬프트 문구 |
| 김태희 | FE 리드 | 라우팅 · 서버 상태 · 계약 소비 · E2E |
| 김경무 | FE | 화면 구현 · 디자인 · 발표 |

협업 규칙(그라운드 룰)은 저장소 루트 `CONTRIBUTING.md` 로 따로 씁니다([#442](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/442)).

## 3. 아키텍처

### 기술 결정

| 영역 | 결정 |
| --- | --- |
| 백엔드 | ✅ Spring Boot 4.1 / Java 21 · PostgreSQL 17 · Flyway · 세션은 JDBC |
| 프론트엔드 | ✅ React 19 + TypeScript + Vite · TanStack Query · React Router |
| AI 서비스 | ✅ FastAPI · Pydantic · OpenAI SDK · **무상태(DB 없음)** |
| LLM | ✅ `gpt-5.6-luna` 하나(Elice ML API 게이트웨이). 모델 6종을 견줘 비용 1/7~1/10 에 품질이 같거나 나아서 고름(10/01) |
| 매칭 검색 | ✅ 임베딩 `text-embedding-3-small` + Chroma 색인 · 🟡 한국어 모델 교체 검토([#320](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/320)) |
| 배포 | ✅ 카테캠 EC2 1대(t3.medium) · Docker Compose · Caddy(HTTPS·정적 파일·`/api` 전달) · develop 머지 시 자동 배포 |
| 운영 | ✅ CloudWatch 로그·에러 경보 · 매일 DB 백업 · Redis 는 사용자별 호출 제한에만 |

### 서비스 경계

**`ai` 는 바깥(GitHub·모델)을 상대하고, `backend` 는 안(DB·세션·Job·규칙·검증)을 지킵니다.**

```
[web · React SPA]  ── Caddy 가 같은 주소로 제공 (/ = 화면, /api = Spring)
        │  REST { data, error } · 세션 쿠키 · CSRF
        ▼
[backend · Spring Boot]   DB · 세션 · GitHub 토큰(암호화) · Job 전부 여기
   consent    GitHub 로그인(권한 `read:user` 하나) · 동의 · 토큰
   ingest     AI 가 올린 원본 저장 · 본인 기여 판정
   recommend  후보 점수 · 경험 후보 보드            (LLM 안 부름)
   job        분석·카드 초안 Job · 재시도 · 멈춘 Job 정리
   card       카드 · 버전 · 근거 sha 확인 · 되묻기 · 확정   ★ 근거 검사는 여기
   matching   공고 가져오기 · 조건 저장 · 매칭 등급
   agent      AI 서버 호출 어댑터 (다른 업무 모듈을 참조하지 않음)
   usage · audit   화면 사용 지표 · 감사 기록
        │  내부 REST · 내부 키 · GitHub 토큰은 헤더로만
        ▼
[ai · FastAPI]   포트를 밖에 열지 않음
   /internal/collect · /collect/candidate-details      GitHub 수집
   /internal/analysis/groups · /diff-evidence · /star   경험 묶기 · diff 요약 · STAR 초안
   /internal/interview-turns · -answer-evaluations · -measured-sentence   되묻기
   /internal/job-postings/extract                        공고 조건 뽑기(원문 인용)
   /internal/matching/postings/index · /prune · /search  공고 색인·검색
        ▼
[PostgreSQL]  [Chroma 색인(볼륨)]  [Redis — 호출 제한]
```

**이 구조에서 얻은 것**
1. **근거 검사를 AI 가 우회할 수 없다.** AI 는 근거 sha 를 주장만 하고, DB 를 가진 Spring 이 확인합니다. `ModuleBoundaryTest` 가 근거 확인 코드가 LLM 을 부르지 못하게 막습니다.
2. **GitHub 클라이언트가 한 벌이다.** 페이지네이션·한도 버그를 한 곳에서만 잡습니다.
3. **저장 책임이 한 곳이다.** AI 가 무상태라 트랜잭션·감사 기록이 갈라지지 않습니다.

## 4. 데이터 모델

테이블 27개(세션 제외). 마이그레이션은 `backend/src/main/resources/db/migration` 이 정본입니다.

| 구간 | 테이블 |
| --- | --- |
| GitHub 원본 | `users` `github_connection` `repository` `user_repository` `git_commit` `commit_file` `pull_request` `pr_review` `pr_review_comment` `issue` `collection_run` |
| 후보 | `candidate` `candidate_commit` `candidate_grouping` |
| 카드 | `card` `card_version` `card_statement` `statement_evidence` `card_slot_note` `interview_turn` `interview_option` |
| 매칭 | `job_posting_source` `job_posting` `job_posting_criterion` |
| 운영 | `analysis_job` `usage_event` `audit_event` |

**핵심 결정**
1. **카드는 칸이 아니라 문장 단위로 저장한다.** "A 칸 3문장 중 2문장만 근거 있음"을 표현하려면 문장 하나가 행 하나여야 합니다.
2. **근거는 커밋 또는 되묻기 답 중 하나를 반드시 갖는다.** 커밋만 인정하면 정성 경험이 통째로 지워집니다. 둘 다 없는 문장은 화면에 뜨지 않습니다.
3. **사용자 개입을 행으로 남긴다.** 후보에서 뺀 커밋·직접 더한 후보가 남아야 오탐·놓침을 셀 수 있습니다.

**지키는 규칙** — AI 초안 버전은 덮어쓰지 않고 새 버전으로 쌓는다 · 사용자 문장은 윤문하지 않는다 · 확정은 사용자 요청으로만 · GitHub 권한이 `read:user` 뿐이라 비공개 저장소는 읽지 못한다.

## 5. 인터페이스 원칙

- **값은 영문 대문자 enum, 한국어는 화면에서만.** 문구를 다듬다가 로직이 깨지지 않게 합니다. (`TECH`/`QUALITATIVE`, `DRAFT`/`CONFIRMED`, `PR`/`ISSUE`/`COMMIT_CLUSTER` …)
- **`COMMIT_CLUSTER` 를 화면에서 "PR" 로 부르지 않는다.** 없던 PR 이 있었던 것처럼 보이면 그게 막으려던 실패입니다.
- **빈 결과는 에러가 아니다.** 후보 0개는 `200` + `verdict: "EMPTY"`, 근거 없어 비운 칸은 `200` + `droppedFields`, Job 실패는 `200` + `state: "FAILED"` 와 오류 코드.
- **오류 코드는 다시 시도 여부를 함께 준다.** 예: AI 일시 장애 `AI_UNAVAILABLE`(retryable), 호출 한도 `429 RATE_LIMITED` + `retryAfterSec`.
- **계약이 코드와 어긋나면 시험이 실패한다.** OpenAPI 스냅샷 시험(BE·AI), `contracts/` 예시를 양쪽 시험이 읽음.

## 6. 리스크

| # | 리스크 | 대응 | 지금 |
| --- | --- | --- | --- |
| R-1 | ⭐ 기여 부풀리기 | 본인 기여 필터(코드) + 후보 고르기(사람) | ✅ 장치 있음 · ⚠️ 평가셋 케이스 5 판정 기록 없음 |
| R-2 | ⭐ PR 0건 저장소 | 커밋 묶음 후보 | ✅ 같은 PR 커밋을 함께 묶은 비율 81%(10/24) · 짧은 커밋 메시지 저장소는 ⚠️ |
| R-3 | 모델이 GitHub 을 안 읽고 지어냄 | 근거 sha 를 AI·Spring 이 두 번 확인 · 근거 없는 문장 비움 | ✅ · 🟡 도구 호출은 횟수(`tool_calls_made`)만 남음 |
| R-4 | 비용 초과 | 2단 읽기 · 한 번에 카드 8장 · 사용자별 호출 제한 | ✅ 카드 한 장 약 8.3원 |
| R-5 | GitHub 한도·수집 상한 | 한도는 `GITHUB_RATE_LIMITED`, 상한은 `COLLECTION_CAPPED` 로 알리고 읽은 데까지 보여 줌 | ✅ (ETag 증분은 9/15 범위 제외) |
| R-6 | 단계 정확도가 곱해진다(6단계 × 90% = 53%) | 실패를 다음 단계로 넘기지 않음 · 사람 게이트 2곳 · 실패 원인별 다시 시도 | ✅ · 초안 다시 만들기는 [#425](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/425) |
| R-7 | 서비스 3개 · 언어 3개 통합 붕괴 | OpenAPI 스냅샷 · 공유 계약 예시 · 가짜 GitHub·LLM 으로 도는 실제 스택 E2E | ✅ |
| R-8 | 평가를 쓰는 사람과 판정하는 사람이 같다 | 판정 주체를 파트 밖으로 | 🟡 |
| R-9 | 다시 오지 않음 | 2년 전 저장소도 동작 · 매칭으로 다시 올 이유 | ⚠️ 측정 전 |
| R-10 | 배포하면 로그인이 안 됨(교차 출처 쿠키) | Caddy 로 화면과 API 를 같은 주소에 | ✅ 10/05 운영 로그인 왕복 확인 |
| R-11 | 한국어 매칭 품질 | 원문 인용을 같이 보여 줌 · 한국어 모델 검토 | ⚠️ 지금 모델 한국어 MRR 0.32, 후보 모델 0.70 |
| R-12 | 서버 한 대 | 매일 백업 · CloudWatch 경보 · 자동 배포와 롤백 | ✅ 백업이 같은 서버 안에만 있음 ⚠️ |

## 7. 실패했을 때 사용자가 보는 것

| # | 무슨 일 | 화면 | 다음 단계로 안 새게 하는 법 |
| --- | --- | --- | --- |
| E-1 | GitHub 수집 실패 | 저장소를 읽지 못했다는 안내와 다시 시도 | 일부만 읽은 것으로 후보를 만들지 않음 |
| E-2 | GitHub 한도·수집 상한 | 읽은 데까지의 후보 + 언제 다시 할 수 있는지 | `partial` 로 표시, "이게 전부"라고 말하지 않음 |
| E-3 | 후보 0개 | 이유 + 파일 보며 찾기 · 직접 입력 · 다른 저장소 | 에러로 던지지 않음 |
| E-4 | 상관없는 커밋이 한 묶음 | 묶인 커밋을 펼쳐 뺌 | 후보 고르기가 초안 전에 막음 |
| E-5 | 근거 0개 칸 | 칸을 비우고 되묻기 버튼 | 코드가 지움 — 모델 판단에 맡기지 않음 |
| E-6 | AI 일시 장애 | 다시 시도할 수 있다는 안내 | Job 을 `AI_UNAVAILABLE`(retryable)로 끝냄 |
| E-7 | 카드 초안 실패 | 원인과 '초안 다시 만들기' | 실패한 카드만 다시, 사용자가 쓴 칸은 그대로(#425) |
| E-8 | 모델이 지어냄 | 화면으로는 멀쩡해 보임 | 근거 sha 이중 확인 · 출처 없는 숫자는 '확인 필요' |
| E-9 | 잘못 확정 | 다시 열기 · 예전 버전 보기·되돌리기 | 확정 버전과 AI 초안이 히스토리에 남음 |
| E-10 | 호출 한도 초과 | 몇 초 뒤 다시 할 수 있는지 | `429` 에 `retryAfterSec` |

## 8. 트레이드오프

1. **코드를 언제 얼마나 읽나** → **2단 읽기.** 후보를 만들 때는 커밋 메시지·PR 같은 메타데이터만 보고, 사용자가 고른 후보만 코드 변경(diff)까지 읽습니다. 비용이 사용자 선택에서 잘립니다.
2. **사람을 몇 곳에 세우나** → **2곳.** 카드 범위(후보 고르기)와 책임(확정). 나머지는 자동.
3. **후보 묶기를 규칙으로 할까 LLM 으로 할까** → 9/1 에는 규칙만이었으나, PR 없는 저장소에서 규칙 묶음이 약해 **규칙으로 거른 뒤 LLM 이 묶고 설명**하게 바꿨습니다. LLM 이 번호를 빠뜨리거나 지어내면 코드가 단독 경험으로 살립니다.
4. **정성 경험을 어디서 만드나** → **사용자 답에서만.** 코드만 읽고 "팀원을 설득했다"가 나오면 창작입니다.
5. **AI 를 백엔드에서 분리하나** → **분리.** 근거 검사가 서비스 경계로 강제됩니다.
6. **LangChain 같은 프레임워크를 쓰나** → **SDK 직접 사용.** 도구 호출이 추상화 뒤에 숨으면 "모델이 실제로 읽었나"를 보이기 어렵습니다.
7. **매칭 기준을 기업 인재상으로 할까 공고 직무 조건으로 할까** → **공고 직무 조건.** 원문 인용으로 검증할 수 있고, 카드 문장과 1:1 로 이을 수 있습니다(10/15 결정).
8. **자소서 문장까지 만드나** → 9/1 에는 대필 논란을 피하려 카드까지만이었으나, 10/25 멘토 피드백으로 **확정 카드를 근거로 한 초안**을 다시 검토 중입니다([#433](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/433)).

## 9. 테스트 전략

| 계층 | 대상 | 방식 · 규모(10/25) |
| --- | --- | --- |
| BE 단위·통합 | 본인 기여 · 후보 점수 · **근거 sha 확인** · Job · 소유권 · 호출 제한 | JUnit · Testcontainers(PostgreSQL) · 약 2,800개 |
| 모듈 경계 | 규칙·근거 코드가 LLM 을 못 부름 | ArchUnit `ModuleBoundaryTest` |
| AI | 근거 검사 · 비밀값 가림 · 지시 주입 · 실패 로그 · 실행기 마감 | pytest · 약 690개 |
| 계약 | BE·AI OpenAPI · 공유 예시 | 스냅샷과 다르면 실패 |
| FE | 화면 · 스키마 · 목 서버 계약 | Vitest 약 370개 · tsc |
| E2E | 화면 흐름(목 서버) · 실제 스택(가짜 GitHub·LLM) 로그인 → 카드 확정 | Playwright 170개 · 실제 스택 1개 · 🟡 더하기 [#444](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/444) |
| AI 품질 | 모델 선택 · 비용 · STAR 초안 · 매칭 · 지시 주입 · PR 없는 저장소 묶기 | [평가 결과표](../../ai/evals/RESULTS.md) |

**평가셋 11개 — 7개가 실패를 유도하는 케이스.** 잘 될 유형이 아니라 안 될 유형부터 골랐습니다. 커밋 메시지가 "fix"뿐 · 혼자 한 프로젝트에 "협업 갈등" · 내 커밋 0개 · 봇 커밋 대부분 · ⭐ 내 커밋 3개 / 팀 180개 · 정량 성과 없음 · README 의 숫자 인용 · 결과가 다른 PR 에 있음 · ⭐ PR 0건. 🟡 케이스별 최신 판정 기록을 결과표에 모아야 합니다.

## 10. 일정과 지표

| 날짜 | 한 일 |
| --- | --- |
| 9/1 | 2단계 착수 |
| 10/05 | 첫 운영 배포(EC2 · HTTPS) · 자동 배포 |
| 10/13 | LLM 게이트웨이 연결 · 경험 묶기 켬 |
| 10/17~18 | 카드 초안 Job 에 AI STAR 초안 연결(#212) · 운영 스위치 추가(#219) |
| 10/24 | 공고 매칭 운영 화면에서 켬 |
| 10/27~ | 🟡 기능 동결 · 실패 화면·문서·발표 준비 |
| 11/11 | 최종 제출 |

**모으는 지표** — 화면 이벤트는 `usage_event` 에 저장합니다(이름은 `frontend/src/lib/track.ts` 와 BE 허용 목록이 같아야 하며 시험이 대조).
퍼널: `repo_selected` → `candidates_shown` → `candidates_confirmed` → `draft_generated` → `interview_asked`·`interview_answered` → `card_confirmed` → `match_opened`

| 지표 | 계산 |
| --- | --- |
| 저장소당 확정 카드 | `card_confirmed` / `repo_selected` |
| 무수정 확정률 | `card_confirmed` 중 `edited=false` |
| 오탐 · 놓침 | `candidate_excluded`·`cluster_commit_excluded` / 제시 후보 · `candidate_added_manually` / 확정 후보 |
| 매칭 사용 | `match_opened` · `match_card_opened` · `match_source_opened` |
| ⚠️ 못 채운 값 | "시간이 줄었다"의 기준선 · 학생 저장소의 PR 보유율 |

## 11. 9/1 판에서 바뀐 것

| 9/1 판 | 지금 | 이유 |
| --- | --- | --- |
| Spring Boot 3 | Spring Boot 4.1 | — |
| 모델 `gpt-4.1-mini` 잠정 | `gpt-5.6-luna` | 6종 비교 결과(10/01) |
| ETag 304 증분 수집 | 범위에서 뺌 | 9/15 결정 |
| 후보 묶기는 규칙만 | 규칙 + LLM 묶기 | PR 없는 저장소 |
| 되묻기 상한 미정 | 카드당 3번(설정) | 🟡 늘리기 검토 [#431](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/431) |
| 배포 대상 검토 중 | EC2 1대 + Caddy | 카테캠 제공 환경 |
| 매칭 없음(인재상 자동 삽입은 스펙 아웃) | 공고 직무 조건 매칭 | 지원까지 잇기 · 원문 인용으로 검증 가능 |
| 확정은 되돌릴 수 없음 | 다시 열기 · 버전 되돌리기 | 잘못 확정 복구(E-9) |

**스펙 아웃(그대로)** — 프로젝트 종료 감지 알림 · 언어별 AST 파서 · 자료 첨부 파서(PDF·URL) · 비공개 저장소 · 승인 없는 자동 확정 · 기업 인재상 자동 삽입 · 합격 자소서 예시·AI 문체 판별.
