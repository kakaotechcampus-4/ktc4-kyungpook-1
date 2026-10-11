# 저장소 후보 1건당 모델 운영 비용 비교

측정일: 2026-10-07

fixture: `PR58`

GPT 계열: `reasoning_effort=medium`

Claude 계열: 네이티브 Messages API 기본 추론값(`reasoning_effort`는 API가 거절)

## 측정 범위

이 문서에서 말하는 "저장소 1건 비용"은 저장소 전체 후보를 모두 카드로 만드는 비용이
아니다. **사용자가 후보 1건을 선택해 카드 1장을 완성하는 흐름**을 뜻한다.

- diff 요약 1회
- STAR 초안 1회
- 질문 생성 3회
- 답변 충분성 판정 3회
- 합계 8회

GitHub 원본 수집과 후보 압축은 규칙 기반이므로 모델 호출 비용은 0원이다. PR 없는
시나리오는 같은 커밋 입력에서 PR 메타데이터만 제거한 합성 `COMMIT_CLUSTER` 사례다.

## 완료된 실측

| 모델 | 전송 방식 | PR 후보 | COMMIT_CLUSTER | 상태 |
|---|---|---:|---:|---|
| GPT-5.6 Luna | OpenAI 호환 | **₩8.31** | **₩8.37** | 16회 완료 |
| GPT-5.6 Terra | OpenAI 호환 | ₩73.01 | ₩74.33 | 16회 완료 |
| Claude Sonnet 5 | 네이티브 Messages | ₩120.34 | ₩120.83 | 16회 완료 |
| GPT-5.6 Sol | OpenAI 호환 | ₩159.47 | ₩148.73 | 16회 완료 |
| Claude Opus 5 | 네이티브 Messages | 측정 중단 | 측정 중단 | 구조화 계약 위반 |
| Claude Fable 5 | 네이티브 Messages | 측정 중단 | 측정 중단 | 최신 재실행 2회 모두 구조화 계약 위반 |

완료된 네 모델에서는 Luna가 가장 저렴했다. 같은 흐름에서 Terra는 Luna의 약 8.8배,
Sonnet은 약 14.5배, Sol은 약 17.8~19.2배였다.

## 토큰·지연

| 모델·시나리오 | 입력 토큰 | 출력 토큰 | 8회 누적 지연 | 추정 비용 |
|---|---:|---:|---:|---:|
| Luna · PR | 16,353 | 1,829 | 21.79초* | ₩8.31 |
| Luna · COMMIT_CLUSTER | 16,340 | 1,860 | 24.03초* | ₩8.37 |
| Terra · PR | 16,346 | 1,272 | 17.61초* | ₩73.01 |
| Terra · COMMIT_CLUSTER | 16,347 | 1,344 | 16.84초* | ₩74.33 |
| Sol · PR | 16,346 | 1,968 | 28.54초* | ₩159.47 |
| Sol · COMMIT_CLUSTER | 16,347 | 1,615 | 23.38초* | ₩148.73 |
| Sonnet · PR | 28,365 | 2,231 | 29.00초 | ₩120.34 |
| Sonnet · COMMIT_CLUSTER | 28,367 | 2,263 | 28.60초 | ₩120.83 |

\* GPT 계열의 이번 비용 실행은 B-2 세 호출의 지연 측정 보완 전에 수행되어 표의 누적
지연에서 B-2 시간이 빠졌다. 토큰과 비용에는 B-2가 포함되어 있어 비용 비교에는 영향이
없다. 다음 재실행부터는 여덟 호출의 지연을 모두 기록한다.

Claude 입력 토큰은 `input_tokens + cache_creation_input_tokens +
cache_read_input_tokens`를 합산했다. Elice가 캐시 토큰에 별도 할인 단가를 적용한다면 실제
콘솔 차감액은 표보다 낮을 수 있다. 공개된 일반 입력 단가를 모두 적용한 보수적 추정치다.

## Opus·Fable을 완료 비용으로 적지 않은 이유

- Opus는 강제 tool 호출의 값을 `arguments`로 한 겹 감싸는 차이는 전송 호환 처리했지만,
  이후 출력에 계약 외 `parameter` 필드를 추가해 strict Pydantic 검증에서 중단됐다.
- Fable은 최신 재실행에서 STAR 출력에 계약 외 빈 키 또는 `status_note`를 추가해 두 번
  모두 중단됐다.
- 계약 외 필드를 임의 삭제하고 비용만 재면 실제 운영 성공률을 숨기므로 완료 비용으로
  기록하지 않았다.

단가 규모만 비교하기 위해 Sonnet과 **동일한 입력·출력 토큰 수를 사용한다고 가정**하면
Opus는 PR 약 ₩300.83 / COMMIT_CLUSTER 약 ₩302.06, Fable은 PR 약 ₩601.69 /
COMMIT_CLUSTER 약 ₩604.16이다. 이는 실측 완료 비용이 아니라 단순 환산치다.

## 재현

```bash
cd ai
set -a; source .env; set +a
python scripts/build_repo_eval_fixtures.py
python scripts/measure_repository_model_cost.py \
  --all-models \
  --workers 3 \
  --case-id PR58 \
  --output evals/results/repository-cost.json
```

`--model-name "GPT-5.6 Luna"`처럼 옵션을 반복하면 일부 모델만 실행할 수 있다. Claude
계열은 스크립트가 네이티브 Messages API와 강제 tool schema를 사용하고, GPT 계열은
OpenAI 호환 구조화 출력을 사용한다.

원본 결과 JSON은 API 응답 비교용 임시 산출물이므로 Git에는 포함하지 않는다. 집계값과
실패 원인만 `evals/results/latest/repository_cost_manifest.json`에 공유한다.
