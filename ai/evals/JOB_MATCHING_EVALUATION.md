# 기업·직무 매칭 임베딩 평가

## 목적

확정 STAR 문장과 채용공고의 세부 조건을 벡터 검색할 때 사용할 거리 상한의
초깃값을 정한다. 이 평가는 합격 가능성을 예측하지 않으며, 검색된 근거를 사용자에게
보여줄 수 있는지를 확인한다.

## 평가 데이터와 모델

- 가상의 공고 30건, 질의 30건 (`job_matching_cases.json`)
- 공고마다 담당업무·필수조건·우대조건·기술 5개, 총 150개 조건
- 로컬 실임베딩: Chroma 기본 ONNX `all-MiniLM-L6-v2`
- 거리: Chroma cosine distance
- 실행일: 2026-10-11

실제 회사나 채용 정보를 평가 데이터로 사용하지 않았다. 로컬 모델은 운영 임베딩
모델을 대신 확정하기 위한 것이 아니라, 실제 벡터와 거리로 검색 파이프라인과 임계값
조정 절차를 검증하기 위한 기준선이다.

## 거리 상한 비교

| 최대 거리 | Recall@1 | Recall@3 | Recall@5 | MRR | 평균 반환 공고 수 | 미검색 사례 |
|---:|---:|---:|---:|---:|---:|---:|
| 0.25 | 0.600 | 0.633 | 0.667 | 0.625 | 1.40 | 10 |
| 0.30 | 0.600 | 0.633 | 0.667 | 0.625 | 1.47 | 10 |
| **0.40** | **0.800** | **0.900** | **0.933** | **0.858** | **2.07** | **2** |
| 0.50 | 0.800 | 0.900 | 0.933 | 0.858 | 2.57 | 2 |
| 0.60 | 0.767 | 0.900 | 0.933 | 0.842 | 3.10 | 2 |

`0.4`와 `0.5`의 검색 정확도는 같았지만 `0.4`가 질의당 불필요한 후보를 평균
0.5개 덜 반환했다. 따라서 `GITORY_MATCH_MAX_DISTANCE=0.4`를 초기 권장값으로
유지한다.

## A~D 등급

거리 상한은 “이 근거를 연결할지”를 정하고, A~D는 연결된 근거의 밀도를 설명한다.
두 의미를 섞지 않는다. 현재 초기 정책은 다음과 같다.

- A: 근거 4개 이상이며 필수조건 근거가 있음
- B: 근거 3개 이상
- C: 근거 1개 이상
- D: 근거 없음

기준은 `GITORY_MATCH_GRADE_A_MIN`, `GITORY_MATCH_GRADE_B_MIN`,
`GITORY_MATCH_GRADE_C_MIN`으로 분리했다. 현재 검색 API는 근거가 하나 이상인 공고만
반환하므로 D는 “추천 결과에 없음”을 뜻한다. 화면에서 전체 공고에 D를 표시하려면
Spring이 전체 활성 공고 목록과 검색 결과를 합쳐야 한다.

이 등급은 점수나 합격 확률이 아니다. 실제 사용자 카드·실제 공고로 라벨링한 데이터가
쌓이기 전에는 임계값을 품질 수치처럼 홍보하지 않는다.

## 재현

```bash
cd ai
python -m scripts.evaluate_job_matching \
  --provider chroma-default \
  --maximum-distance 0.4 \
  --output evals/results/job_matching-chroma-default-0.4.json
```

운영 임베딩 모델을 확정하면 같은 fixture를 `--provider openai`로 다시 실행하고,
그 결과로 `GITORY_MATCH_MAX_DISTANCE`를 재확정한다.

## 결과 파일

- `evals/results/job_matching-chroma-default-0.25.json`
- `evals/results/job_matching-chroma-default-0.3.json`
- `evals/results/job_matching-chroma-default-0.4.json`
- `evals/results/job_matching-chroma-default-0.5.json`
- `evals/results/job_matching-chroma-default-0.6.json`

