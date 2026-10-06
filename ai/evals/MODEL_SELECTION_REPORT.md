# LLM 모델 선택 보고서

작성일: 2026-10-01 · 대상: Gitory AI 서버(`ai/`)의 LLM 작업 전체

## 1. 결론

GPT-5.6 Luna `medium`을 운영 기본 **후보**로 권장한다. Sonnet·Opus를 포함한 최종 운영 모델 선택은 팀이 품질·비용·지연·구조화 출력 안정성의 우선순위를 논의한 뒤 확정한다.

| 작업 | 모델 · 강도 | 호출당 비용 | 근거 |
|---|---|---|---|
| B-2 답변 충분성 판정 | Luna · medium | ₩0.32 | 기준 반영 후 50건×3회 100% |
| diff 분석 (DiffAnalyzer) | Luna · medium | ₩3.2 | 정답 포인트 86%, Terra(80%)와 같은 수준, high 대비 지연 절반 |
| STAR 초안 (StarGenerator) | Luna · medium | ₩1.62 | 최신 근거 기준 10/10 통과 |
| 질문 생성 (B-1·B-2 후속 질문) | Luna · medium | ₩0.46 | 최신 프롬프트 28/30 통과, Sonnet보다 높은 통과율 |

- 모든 작업에서 Luna가 Terra와 같거나 약간 나았고, 비용은 Terra의 약 1/7~1/10이다.
- "많은 데이터를 읽는 작업은 high"라는 초기 가설은 확인되지 않았다. diff 분석에서 high는 medium보다 포인트 언급률이 2%p 높았지만, 지연이 2배(12.7초 대 6.8초)였고 SHA 복사 오류가 1건 있었다.
- 이번 결과로 말할 수 있는 범위는 "Luna가 Terra만큼 한다", "최신 STAR·질문 생성 사례에서 Sonnet보다 높은 계약 통과율을 보였다", "Opus는 복잡한 STAR 구조화 출력의 신뢰성이 부족했다"까지다. Luna를 기본 후보로 두되, 운영 선택은 팀 논의 후 확정한다.

## 2. 비교 대상과 공통 조건

| 모델 | 모델 ID | 입력 단가(1M) | 출력 단가(1M) | 비고 |
|---|---|---|---|---|
| Claude Opus 5 | claude-opus-5 | ₩7,612 | ₩38,062 | |
| Claude Sonnet 5 | claude-sonnet-5 | ₩3,045 | ₩15,225 | |
| Claude Fable 5 | claude-fable-5 | ₩15,225 | ₩76,125 | |
| GPT-5.6 Terra | gpt-5.6-terra | ₩3,045 | ₩18,270 | |
| GPT-5.6 Sol | gpt-5.6-sol | ₩6,090 | ₩30,450 | |
| GPT-5.6 Luna | gpt-5.6-luna | ₩304 | ₩1,827 | |

공통 조건:
- Elice AI Cloud Serverless, OpenAI 호환 `/v1/chat/completions` + 구조화 출력(`response_format`)
- `temperature` 미지정(기본값): GPT-5.6 계열이 `temperature=0`을 400으로 거절해 모든 모델에서 제외함
- 결과가 매번 다를 수 있어 같은 사례를 3회씩 반복함
- 비용은 위 단가로 계산한 추정치이며, 실제 차감액은 Elice 콘솔을 기준으로 봐야 함

## 3. 실험 1: B-2 답변 충분성 판정 (6개 모델)

### 3-1. 기본 사례 11건 × 1회 (medium)

| 모델 | 정확도 | 완료 | 평균 지연 | 11건 비용 |
|---|---|---|---|---|
| GPT-5.6 Luna | 100% | 11/11 | 2.1초 | ₩2.6 |
| GPT-5.6 Terra | 100% | 11/11 | 1.9초 | ₩24.9 |
| GPT-5.6 Sol | 100% | 11/11 | 2.4초 | ₩45.9 |
| Claude Opus 5 | 100% | 11/11 | 3.4초 | ₩156.3 |
| Claude Fable 5 | 100% | 11/11 | 7.2초 | ₩318.8 |
| Claude Sonnet 5 | 81.8% | 9/11 | 5.8초 | ₩58.0 |

쉬운 사례뿐이라 모델 간 차이가 드러나지 않았다.

### 3-2. 경계 사례 25건 추가, 36건 × 3회 (기준 명시 전 프롬프트)

| 모델 | 전체 | 기존 11건 | 추가 25건 | 형식 오류 | 결과가 바뀐 사례 | 평균 지연 | 호출당 비용 |
|---|---|---|---|---|---|---|---|
| GPT-5.6 Terra | 100% | 100% | 100% | 0 | 0 | 2.0초 | ₩2.3 |
| Claude Opus 5 | 100% | 100% | 100% | 0 | 0 | 3.4초 | ₩14.7 |
| Claude Fable 5 | 100% | 100% | 100% | 0 | 0 | 4.6초 | ₩28.9 |
| GPT-5.6 Sol | 97.2% | 100% | 96.0% | 0 | 0 | 2.5초 | ₩4.5 |
| GPT-5.6 Luna | 95.4% | 100% | 93.3% | 0 | 1 | 2.0초 | ₩0.25 |
| Claude Sonnet 5 | 92.6% | 87.9% | 94.7% | 8 | 3 | 2.8초 | ₩5.7 |

- Sol과 Luna는 "전반적으로 느렸어요"(S), "저희 팀이 맡았어요"(T)를 ANSWERED로 판정했다. 추론 능력의 문제가 아니라, 얼마나 구체적이어야 충분한지를 프롬프트가 정하지 않아서 모델마다 해석이 갈린 것이다.
- Luna는 판정 이유에 "책임이 설명되지 않았다"고 쓰고 결과는 ANSWERED로 낸 경우가 있었다. 이유와 결과가 어긋날 수 있으므로 운영에서는 결과만 쓰고 이유는 로그로만 남긴다.

### 3-3. 판정 기준 명시 후 50건 × 3회 (확인용 14건 포함)

기준은 `ANSWER_SUFFICIENCY_CRITERIA.md`에 정의했다. 확인용 14건은 프롬프트를 쓸 때 보지 않은 사례다.

| 모델 | 전체 | 기존 11건 | 추가 25건 | 확인용 14건 | 형식 오류 | 결과가 바뀐 사례 | 평균 지연 | 호출당 비용 |
|---|---|---|---|---|---|---|---|---|
| **GPT-5.6 Luna** | **100%** | 100% | 100% | 100% | 0 | 0 | 1.8초 | **₩0.32** |
| GPT-5.6 Sol | 100% | 100% | 100% | 100% | 0 | 0 | 2.1초 | ₩5.58 |
| Claude Opus 5 | 100% | 100% | 100% | 100% | 0 | 0 | 2.7초 | ₩16.64 |
| Claude Fable 5 | 100% | 100% | 100% | 100% | 0 | 0 | 4.5초 | ₩33.40 |
| GPT-5.6 Terra | 98.7% | 100% | 97.3% | 100% | 0 | 1 | 1.8초 | ₩2.97 |
| Claude Sonnet 5 | 94.0% | 100% | 92.0% | 92.9% | 9 | 2 | 2.6초 | ₩6.92 |

- 기준을 명시하자 Luna와 Sol이 틀리던 유형을 모두 맞혔다. 차이의 원인이 모델이 아니라 기준 부재였다는 뜻이다.
- Terra는 "p6spy로 보니 N+1이라 fetch join 걸었어요"(A)를 2회 INSUFFICIENT로 판정했다. S에만 적용되는 "대상 특정" 요소를 A에도 요구한 과잉 엄격이다.

## 4. 실험 2: 전체 LLM 작업 (Luna와 Terra)

### 4-1. 조건
- 입력: 우리 저장소(`kakaotechcampus-4/ktc4-kyungpook-1`)의 PR 10건. 크기와 영역을 섞었다.
  - 작은 PR: #46 프론트, #11 백엔드, #35 백엔드
  - 중간 PR: #15, #12 (백엔드)
  - 큰 PR: #21 AI, #58 AI, #50 백엔드, #17 백엔드
  - 대부분 잘린 PR: #52 프론트
- 로컬 git에서 입력을 만들었다. PR당 patch는 최대 24,000자이고 커밋마다 균등하게 나눴다. 비밀값으로 보이는 문자열은 가렸다.
- 정답 포인트: `fixtures/task_gold.json` (PR마다 2~7개, 총 43개)
- 채점: 코드 자동 검사 + 사람(Claude)이 정답 포인트와 직접 대조. 채점용 LLM은 크레딧 절약을 위해 쓰지 않았다.
- 제외한 모델: Claude(이유는 6절), Sol(Terra의 2배 가격인데 B-2에서 나은 점이 없었음)

### 4-2. diff 분석 (PR 10건 × 1회)

| 조합 | 자동 검사 통과 | 정답 포인트 언급 | 근거 없는 주장 | 평균 지연 | 평균 입력/출력 토큰 | 호출당 비용 |
|---|---|---|---|---|---|---|
| **Luna · medium** | 90% | **37/43 (86%)** | 0 | **6.8초** | 7,648 / 495 | **₩3.2** |
| Luna · high | 80% | 38/43 (88%) | 0 | 12.7초 | 7,648 / 1,051 | ₩4.2 |
| Terra · high | 90% | 34.5/43 (80%) | 0 | 10.3초 | 7,648 / 544 | ₩33.2 |

PR별 정답 포인트 언급 수 (Terra high / Luna high / Luna medium):

| PR | 포인트 | Terra high | Luna high | Luna medium | 공통으로 빠진 내용 |
|---|---|---|---|---|---|
| #46 | 3 | 2.5 | 3 | 3 | |
| #11 | 2 | 1 | 1 | 1 | Boot 4에서 자동 설정이 스타터로 옮겨간 이유 |
| #35 | 3 | 3 | 3 | 3 | |
| #15 | 5 | 4 | 4 | 4 | Testcontainers 동시성 테스트 |
| #12 | 5 | 4 | 5 | 4 | |
| #21 | 4 | 3 | 3 | 3 | revert보다 직접 근거를 우선하는 것, PR 번호 인용 |
| #58 | 4 | 3 | 4 | 4 | |
| #50 | 5 | 5 | 5 | 5 | |
| #17 | 7 | 4 | 5 | 5 | 토큰을 프로필과 분리, 미인증 요청에 401 JSON |
| #52 | 5 | 5 | 5 | 5 | |

자동 검사 위반의 내용:
- "지어낸 수치 `2회`" 3건은 오탐이다. 입력에 `MAX_INTERVIEW_TURNS = 2`가 있어서 사실에 맞는 내용이다.
- Luna high의 PR17 1건은 실제 오류다. 커밋 SHA를 한 글자 틀리게 옮겼다.

### 4-3. STAR 초안 (PR 10건 × 3회, medium)

| 모델 | 자동 검사 통과 | S 상태 (채움/검토 필요/비움) | A 채움 | T 생성* | R 근거 기반 생성 | 호출당 비용 |
|---|---|---|---|---|---|---|
| Terra | 100% | 13 / 14 / 3 | 30/30 | 0/30 | 0/30 | ₩9.5 |
| Luna | **100%** | 19 / 8 / 3 | 30/30 | 0/30 | **9/30** | ₩1.4 |

\* 이 실험의 기존 프롬프트는 사용자 답변 유무를 기준으로 일부 슬롯을 비우도록 지시했으므로, T 생성률은 모델 품질 비교 결과가 아니다. 새 공통 근거 기준으로 프롬프트를 바꾼 뒤 별도 재평가가 필요하다.

- Luna가 R을 9번 채운 내용은 **지어낸 것이 아니라 커밋 메시지에 적힌 검증 결과**다(예: "Vitest 32/32, Playwright 23/23 통과"). 모두 입력 SHA가 연결돼 있어 새 기준에서는 유효한 R 초안으로 본다. 따라서 기존 `FILLED_WITHOUT_USER_ANSWER` 9건을 제외하면 Luna의 자동 검사 통과율은 30/30, 100%다.
- **확정 기준:** 사용자 답변 유무가 아니라 근거 연결 여부로 판단한다. S/T/A/R 각 슬롯은 연결 가능한 근거가 있으면 생성하고, 없으면 비운다.

### 4-4. 질문 생성 (30건 × 3회, medium)

사례 구성: 근거 기반 질문 20건(PR별 R과 S 슬롯)과 후속 질문 10건(B-2에서 불충분했던 답변).

| 모델 | 자동 검사 통과 | 오류 | 호출당 비용 | 관찰 |
|---|---|---|---|---|
| Terra | 100% | 0 | ₩3.1 | 가끔 근거에 없는 "사용자 영향", "운영 지표"를 전제함 |
| Luna | 97.8% | 1 (Cloudflare 520 게이트웨이 오류) | ₩0.4 | 커밋 내용을 구체적으로 짚음. 가끔 한 문장에 두 가지를 물음 |

- 후속 질문은 두 모델 모두 빠진 요소를 정확히 짚었다. 예: "전반적으로 느렸어요"에는 "어떤 기능·API에서 어떤 지연이 있었나요?"로 되물었다.
- Luna의 SHA 미인용 1건은 PR 번호만 인용한 경우다. 명세는 "sha 또는 PR 번호 인용"이라 위반이 아닐 수 있다.

### 4-5. 최신 프롬프트 재검증: Luna·Sonnet·Opus (STAR 10건 · 질문 30건 · medium · 각 1회)

STAR 초안과 질문 생성 프롬프트·검사기를 아래 기준으로 보완한 뒤 재검증했다.

- STAR: 사용자 답변 유무가 아니라 commit·PR·review·사용자 답변 중 연결 가능한 근거의 존재 여부로 S/T/A/R 생성 여부를 판단한다.
- 질문: 질문 본문은 반드시 `?`로 끝나고, 탈출 문구는 질문 본문 뒤에 붙는다.
- 근거 인용: 대상 SHA가 있는 사례는 SHA를 정확히 인용해야 한다.

| 작업 | Luna · medium | Sonnet · medium (네이티브 Messages API) | Opus · medium (네이티브 Messages API) |
|---|---:|---:|---:|
| STAR 초안 통과율 | **10/10 (100%)** | 9/10 (90%) | 4/10 (40%, 형식 오류 6건) |
| STAR 초안 평균 지연 | **6.67초** | 7.18초 | 9.93초* |
| STAR 초안 추정 비용/건 | **₩1.62** | ₩17.65 | ₩54.00* |
| 질문 생성 통과율 | 28/30 (93.3%) | 24/30 (80%) | **29/30 (96.7%)** |
| 질문 생성 평균 지연 | 2.72초 | **2.66초** | 3.30초 |
| 질문 생성 추정 비용/건 | **₩0.46** | ₩6.91 | ₩17.13 |

\* Opus STAR의 지연·비용은 형식 오류가 아닌 4건의 성공 응답만 기준으로 계산했다. 형식 오류 응답에도 실제 비용이 발생했을 수 있어, 전체 운영 비용은 표의 값보다 높을 수 있다.

#### 왜 Luna·Sonnet·Opus만 최신 프롬프트로 비교했는가

이 실험은 6개 모델의 전체 순위를 다시 매기는 실험이 아니라, **기존 선별 이후 남은 운영 후보를 최신 프롬프트로 검증하는 실험**이다.

- Luna는 3절의 B-2 6개 모델 비교와 4절의 Luna·Terra 전체 작업 비교에서 가장 낮은 비용으로 동등한 품질을 보인 기본 후보다.
- Sonnet은 OpenAI 호환 구조화 출력 경로에서는 형식 오류가 있었지만, 네이티브 Messages API의 tool schema 호출에서는 정상 구조화 응답을 반환했다. 따라서 "Claude 네이티브 경로를 별도로 유지할 만큼 이점이 있는가"를 확인할 후보였다.
- Opus는 B-2에서는 Luna와 동일 정확도였지만 더 비쌌다. 다만 긴 근거를 읽는 STAR 초안에서 우위일 가능성을 배제할 수 없어, 이번에 같은 최신 사례를 1회 추가 실행했다.
- Terra는 4절에서 Luna와 비슷한 품질이었지만 비용이 약 7~10배 높았다. Sol·Fable도 B-2에서 Luna보다 운영상 이점이 확인되지 않았고 비용이 더 높아, 이번 프롬프트 수정 회귀 비교 대상에서는 제외했다.

즉 이번 세 모델 비교는 비용을 아끼기 위한 임의 축소가 아니라, 기존 광범위 선별 결과를 바탕으로 한 **Luna 대 Claude 네이티브 대안 검증**이다.

#### 실패 사례

Luna 질문 생성의 실패는 SHA 인용 누락 2건이다.

| 사례 | 실제 출력의 문제 | 왜 오류인가 |
|---|---|---|
| PR #58 S | PR 번호만 인용하고 대상 SHA를 생략 | 이 사례는 SHA 기반 근거 질문이므로, 근거 식별자를 정확히 드러내야 한다. |
| PR #50 S | PR 번호만 인용하고 대상 SHA를 생략 | 동일하게 대상 commit과 질문의 연결을 사용자가 확인할 수 없다. |

Sonnet의 실패는 질문 6건, STAR 초안 1건이다.

| 유형 | 건수 | 사례 및 이유 |
|---|---:|---|
| SHA 인용 누락·오기 | 4 | PR #50·#35는 SHA 누락, PR #21은 `340fbdf`를 `340fbde`로, PR #17은 `070f893`을 `0709893`으로 잘못 옮겼다. 다른 commit을 가리킬 수 있는 사실 오류다. |
| 탈출 문구 누락 | 1 | PR #46 S에서 사용자가 기억하지 못할 때 답변을 건너뛸 수 있는 안내가 빠졌다. |
| 질문 종료 규칙 위반 | 1 | PR #11 R의 질문 본문 마지막 문장이 `궁금해요.`로 끝나 `?` 종료 규칙을 지키지 못했다. |
| 근거 없는 수치 추가 | 1 | PR #52 R에서 입력 근거에 없는 `기존 환경 이슈 1건 제외`를 덧붙였다. |

Luna는 최신 실행의 질문 30건 모두 질문 본문 `?` 종료 규칙을 지켰다. Sonnet도 프롬프트에 같은 규칙을 받았지만 위 1건에서 출력이 이를 따르지 않았다.

Opus는 질문 생성에서는 29/30건이 자동 검사를 통과했지만, `FOLLOWUP_R_VAGUE_RESPONSE` 1건에서 도구 입력을 `{"$0": {"question_text": ...}}`처럼 한 단계 감싸 `question_text` 필드를 읽을 수 없게 만들었다. STAR 초안에서는 PR #50·#58·#12·#15·#52·#11의 6건에서 `S` 슬롯에 객체 대신 `<parameter name="status">...` 형태의 문자열을 넣거나 S/T/A/R 바깥에 필드를 배치했다. 네이티브 Messages API를 사용해도 Opus의 복잡한 스키마 형식 오류가 해소되지 않았다는 뜻이다.

#### 해석과 한계

- 최신 실행에서는 Luna의 질문 생성 지연이 2.72초로 Sonnet의 2.66초와 사실상 같았다. 이전 Luna 반복 실행 평균 15.23초는 30초 이상 걸린 요청 13건(최장 123.70초)이 평균을 끌어올린 결과다. 최신 30건에서는 장기 지연이 없었다.
- 이번 최신 비교는 각 사례를 1회만 실행했다. Luna 지연의 p50·p95 안정성은 동일 endpoint에서 3회 반복 측정 후 확정해야 한다.
- Sonnet은 네이티브 Messages API, Luna는 OpenAI 호환 endpoint로 호출했다. 따라서 지연에는 모델 성능뿐 아니라 provider gateway·transport 차이도 포함된다. 이는 실제 운영 경로의 비교라는 의미는 있지만, 순수 모델 추론 속도만의 비교는 아니다.

결론적으로 Sonnet은 질문 생성 속도에서 유의미한 우위를 보이지 못했고, 비용은 약 11~15배 높으며 근거·형식 계약 위반도 더 많았다. Opus는 질문 생성 품질은 높았지만 비용이 약 37배 높고 STAR 구조화 출력 신뢰성이 낮았다. 따라서 Luna를 운영 기본 후보로 권장하며, 최종 모델 선택은 팀 논의 후 확정한다.

## 5. 운영 적용 시 필요한 코드 보완

| # | 내용 | 대상 |
|---|---|---|
| 1 | T/R 프롬프트·검증기를 연결 가능한 근거 유무 기준으로 변경하고 STAR 초안 eval을 재실행 | `evals/tasks/star_draft.py` **완료** |
| 2 | 입력에 없는 SHA는 출력에서 제거 | `star_generator`, `diff_analyzer` |
| 3 | 형식 오류와 5xx 게이트웨이 오류는 1회 재시도하고, 그래도 실패하면 B-2는 INSUFFICIENT로 처리 | 공통 호출기 |
| 4 | 외부 모델로 보내기 전에 diff의 비밀값 가리기 (저장소 테스트 코드에 `gho_…` 형태 문자열 존재) | `diff_analyzer` |
| 5 | 출력 한도 6,000토큰 이하로 설정하거나 스트리밍 사용 | 공통 호출기 |
| 6 | 질문 프롬프트에 "한 문장에 두 가지를 묻지 않는다"와 "질문 본문은 `?`로 끝낸다" 추가 | 질문 생성 (`?` 규칙 완료) |
| 7 | B-2는 판정 결과만 쓰고 판정 이유는 로그로만 남김 | B-2 |
| 8 | 부족한 요소를 짚는 후속 질문으로 교체 (현재는 고정 문구) | `interview_answer_service` |
| 9 | SHA 기반 질문에서 SHA 누락·오기를 deterministic하게 막거나 재생성 | 질문 생성 후검증 |

## 6. Elice 게이트웨이에서 발견한 제약

1. **Claude 모델은 OpenAI 호환 구조화 출력 경로에서 형식 오류가 발생한다.**
   - Sonnet 5는 B-2 판정에서 150회 중 9회, 같은 사례를 연속으로 부르면 6회 중 6회 형식이 깨졌다. 답을 `{"$PARAMETER_NAME": {...}}`, `{"parameter": {...}}`처럼 불필요한 키로 한 번 더 감쌌다.
   - Opus 5는 스키마가 복잡한 diff 분석과 STAR 초안에서 모든 호출이 깨졌다(`{"input": {...}}`로 감싸거나 필드 누락).
   - 같은 Sonnet 5를 네이티브 Messages API의 강제 도구 호출로 부르면 구조화 응답은 정상화됐다. 따라서 Messages API 자체의 문제가 아니라, 이 게이트웨이의 OpenAI 호환 구조화 출력 경로 제약으로 본다.
   - 최신 STAR·질문 생성 재검증에서 Sonnet 네이티브 호출은 JSON 형식 오류 없이 완료됐다. 다만 SHA 인용·질문 형식 같은 **내용 계약** 오류는 남았다.
   - Opus는 네이티브 Messages API의 강제 tool schema 호출에서도 STAR 초안 10건 중 6건을 스키마와 다른 형태로 반환했다. 복잡한 중첩 스키마에 대해서는 네이티브 경로만으로 신뢰성이 확보되지 않았다.
   - 결론: Claude 모델을 운영 후보로 유지하려면 네이티브 Messages API 경로가 따로 필요하지만, 현재 평가만으로는 Luna를 대체할 품질·비용·구조화 출력 신뢰성 이점이 확인되지 않았다.
2. **GPT-5.6 계열은 `temperature=0`을 지원하지 않는다.** 기본값 1만 허용한다.
3. **스트리밍이 아닌 요청은 출력 한도가 6,000토큰까지다.** 그보다 크게 요청하면 400으로 거절된다.
4. **목록에 없는 매개변수는 무시되지 않고 400으로 거절된다.** 허용되는 매개변수와 `reasoning_effort` 값은 모델 문서에 나와 있다.
   - Claude 계열: low / medium / high / xhigh / max
   - GPT-5.6 계열: none / low / medium / high / xhigh
5. **간헐적으로 Cloudflare 520 오류가 난다.** 이번 실험 중 1회 발생했다. 재시도가 필요하다.

## 7. 한계

- 표본이 작다. B-2는 50건, 나머지 작업은 PR 10건이다. 실제 사용자 답변과 다른 저장소로 운영 전에 다시 검증해야 한다.
- 전체 작업 비교의 채점자가 한 명(Claude)이다. 채점용 LLM과의 교차 확인은 하지 않았다.
- 최초 전체 작업 비교에는 상위 모델(Opus, Sol)을 넣지 않았다. 이후 Opus는 최신 STAR·질문 사례에서 1회 추가 실행했으나, Sol은 같은 최신 프롬프트로 재평가하지 않았다. 따라서 "모든 상위 모델보다 낫다"고 일반화할 수는 없다.
- diff 분석은 1회만 실행해서, 결과가 반복마다 흔들리는지는 보지 않았다.
- 자동 검사의 "지어낸 수치" 검사에 오탐이 있다(예: `2회`, `32개`처럼 입력의 숫자를 다르게 표현한 경우).

## 8. 비용

| 실험 | 추정 비용 |
|---|---|
| B-2 1차 (11건) | ₩606 |
| B-2 경계 사례 (36건 × 3회 × 6개 모델) | ₩6,089 |
| B-2 기준 반영 (50건 × 3회 × 6개 모델) | ₩9,811 |
| 전체 작업 비교 (Luna와 Terra) | 약 ₩1,050 |
| 최신 프롬프트 재검증 (Luna·Sonnet·Opus) | 약 ₩1,130 이상 |
| 소규모 테스트, 기록되지 않은 호출(중단된 실행, 형식 오류 응답, 원인 확인용 호출) | 약 ₩1,500 |
| **합계** | **약 ₩20,000 이상** |

운영 비용 참고: Luna medium 기준으로 PR 하나를 처리하면 약 ₩7이 든다(diff 분석 ₩3.2 + STAR 초안 ₩1.4 + 질문 3개 ₩1.2 + 답변 판정 3회 ₩1.0).

## 9. 파일

| 경로 | 내용 |
|---|---|
| `evals/ANSWER_SUFFICIENCY_CRITERIA.md` | B-2 판정 기준 (공통 기준, 슬롯별 최소 요소, 확정 정책) |
| `evals/answer_sufficiency_cases.json` | B-2 사례 50건 (기존 11, 경계 25, 확인용 14) |
| `evals/fixtures/task_gold.json` | PR 10건의 정답 포인트 |
| `evals/tasks/` | 작업별 후보 프롬프트, 출력 스키마, 자동 검사 |
| `services/llm_answer_sufficiency_evaluator.py` | B-2 LLM 판정기 |
| `services/llm_structured_client.py` | 구조화 출력 공통 호출기 |
| `scripts/evaluate_answer_sufficiency_models.py` | B-2 모델 비교 실행기 |
| `scripts/build_repo_eval_fixtures.py` | 로컬 git으로 작업 입력 생성 |
| `scripts/evaluate_llm_tasks.py` | 작업별 모델 비교 실행기 (예산 상한 지원) |
| `scripts/evaluate_claude_messages_tasks.py` | Claude 네이티브 Messages API 작업 비교 실행기 (강제 tool schema) |
| `evals/claude_messages_endpoints.example.json` | Sonnet·Opus 네이티브 Messages API 모델 식별자·환경변수·요청 파라미터 예시 |
| `scripts/judge_llm_task_outputs.py` | LLM 채점기 (이번에는 사용하지 않음) |
| `evals/results/latest/comparison_manifest.json` | 4-5절 최신 비교의 모델·파라미터·사례 목록·집계 결과를 고정한 공유 artifact |
| `evals/results/latest/README.md` | 원본 JSON 결과 생성·공유 규칙 |

재현 방법:

```bash
cd ai
set -a; source .env; set +a
python scripts/evaluate_answer_sufficiency_models.py --repeats 3 --output evals/results/b2.json
python scripts/build_repo_eval_fixtures.py
python scripts/evaluate_llm_tasks.py --task diff_summary --case-ids PR46 PR11 PR35 PR15 PR12 PR21 PR58 PR50 PR17 PR52 \
  --model-names "GPT-5.6 Luna" --reasoning-effort medium --budget 100 --output evals/results/tasks/diff.json
```

### 9-1. 4-5절 STAR·질문 비교 재현

4-5절은 아래 고정 입력으로 실행했다. 사례 전체 목록·집계값·모델 식별자는 `evals/results/latest/comparison_manifest.json`에서 함께 확인한다.

- STAR 10건: `PR58`, `PR50`, `PR21`, `PR52`, `PR46`, `PR35`, `PR17`, `PR15`, `PR12`, `PR11`
- 질문 30건: 위 PR별 R·S 근거 질문 20건 + `FOLLOWUP_*` 후속 질문 10건
- Luna: `gpt-5.6-luna`, OpenAI 호환 endpoint, `reasoning_effort=medium`
- Sonnet: `claude-sonnet-5`, 네이티브 Messages API, 강제 `emit_structured_output` tool schema, 평가 설정 `reasoning_effort=medium`
- Opus: `claude-opus-5`, 네이티브 Messages API, 강제 `emit_structured_output` tool schema, 평가 설정 `reasoning_effort=medium`

실제 URL·API 키를 적지 않은 로컬 설정 파일을 만든다. 키와 endpoint는 환경변수로만 둔다.

```bash
cd ai
cp evals/model_endpoints.example.json evals/model_endpoints.json
cp evals/claude_messages_endpoints.example.json evals/claude_messages_endpoints.json
set -a; source .env; set +a

# origin/develop 기준으로 입력 fixture를 다시 고정
python scripts/build_repo_eval_fixtures.py --out evals/fixtures/generated

# Luna: OpenAI 호환 구조화 출력 경로
python scripts/evaluate_llm_tasks.py --task star_draft \
  --case-ids PR58 PR50 PR21 PR52 PR46 PR35 PR17 PR15 PR12 PR11 \
  --model-names "GPT-5.6 Luna" --reasoning-effort medium --repeats 1 \
  --output evals/results/latest/luna_star_draft.json
python scripts/evaluate_llm_tasks.py --task question_gen \
  --case-ids PR58_R_LINKED_COMMIT PR58_S_NO_EVIDENCE PR50_R_LINKED_COMMIT PR50_S_NO_EVIDENCE PR21_R_LINKED_COMMIT PR21_S_NO_EVIDENCE PR52_R_LINKED_COMMIT PR52_S_NO_EVIDENCE PR46_R_LINKED_COMMIT PR46_S_NO_EVIDENCE PR35_R_LINKED_COMMIT PR35_S_NO_EVIDENCE PR17_R_LINKED_COMMIT PR17_S_NO_EVIDENCE PR15_R_LINKED_COMMIT PR15_S_NO_EVIDENCE PR12_R_LINKED_COMMIT PR12_S_NO_EVIDENCE PR11_R_LINKED_COMMIT PR11_S_NO_EVIDENCE FOLLOWUP_R_VAGUE_RESPONSE FOLLOWUP_S_OFF_TOPIC FOLLOWUP_T_UNCLEAR_RESPONSIBILITY FOLLOWUP_A_ACTION_WITHOUT_REASON FOLLOWUP_PROMPT_INJECTION_NOT_A_ANSWER FOLLOWUP_R_SUBJECTIVE_FEELING_ONLY FOLLOWUP_R_FUTURE_PLAN_NOT_RESULT FOLLOWUP_R_ANSWERED_WITH_ACTION FOLLOWUP_R_OTHER_PROJECT FOLLOWUP_A_GENERIC_PLATITUDE \
  --model-names "GPT-5.6 Luna" --reasoning-effort medium --repeats 1 \
  --output evals/results/latest/luna_question_gen.json

# Sonnet·Opus: 네이티브 Messages API + 강제 tool schema 경로
python scripts/evaluate_claude_messages_tasks.py --task star_draft \
  --case-ids PR58 PR50 PR21 PR52 PR46 PR35 PR17 PR15 PR12 PR11 \
  --model-names "Claude Sonnet 5" "Claude Opus 5" \
  --output evals/results/latest/claude_star_draft.json
python scripts/evaluate_claude_messages_tasks.py --task question_gen \
  --case-ids PR58_R_LINKED_COMMIT PR58_S_NO_EVIDENCE PR50_R_LINKED_COMMIT PR50_S_NO_EVIDENCE PR21_R_LINKED_COMMIT PR21_S_NO_EVIDENCE PR52_R_LINKED_COMMIT PR52_S_NO_EVIDENCE PR46_R_LINKED_COMMIT PR46_S_NO_EVIDENCE PR35_R_LINKED_COMMIT PR35_S_NO_EVIDENCE PR17_R_LINKED_COMMIT PR17_S_NO_EVIDENCE PR15_R_LINKED_COMMIT PR15_S_NO_EVIDENCE PR12_R_LINKED_COMMIT PR12_S_NO_EVIDENCE PR11_R_LINKED_COMMIT PR11_S_NO_EVIDENCE FOLLOWUP_R_VAGUE_RESPONSE FOLLOWUP_S_OFF_TOPIC FOLLOWUP_T_UNCLEAR_RESPONSIBILITY FOLLOWUP_A_ACTION_WITHOUT_REASON FOLLOWUP_PROMPT_INJECTION_NOT_A_ANSWER FOLLOWUP_R_SUBJECTIVE_FEELING_ONLY FOLLOWUP_R_FUTURE_PLAN_NOT_RESULT FOLLOWUP_R_ANSWERED_WITH_ACTION FOLLOWUP_R_OTHER_PROJECT FOLLOWUP_A_GENERIC_PLATITUDE \
  --model-names "Claude Sonnet 5" "Claude Opus 5" \
  --output evals/results/latest/claude_question_gen.json
```

`comparison_manifest.json`은 이번 PR에 커밋하는 공유 artifact다. 실행에서 생성되는 상세 JSON은 모델 원문·저장소 맥락을 포함할 수 있으므로 키 없이 팀의 제한된 공유 공간에 보관하고, PR에는 manifest의 집계·사례 목록만 남긴다.

## 10. 남은 일

1. 팀 논의로 운영 모델을 확정한 뒤, 5절의 코드 보완과 선택 모델 연결을 별도 PR로 진행한다.
2. 비교용 키 `gitory-eval-b2`를 삭제하고, 운영 키를 새로 발급해 배포 환경변수에만 저장한다.
3. 운영 초기에 실제 답변 로그로 판정 결과를 표본 검수하고, 형식 오류 비율을 모니터링한다.
