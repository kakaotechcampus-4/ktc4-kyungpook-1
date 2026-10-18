# AI

Gitory AI 서버의 독립 실행 루트입니다. A(LLM 분석)와 B(Agent/RAG) 코드를
이 디렉터리에서 함께 관리합니다.

## 구조

```text
ai/
├── api/
├── ports/
├── rag/
├── schemas/
├── services/
├── tests/
├── main.py
├── requirements.txt
└── requirements-dev.txt
```

## 로컬 실행

저장소 루트에서 아래와 같이 실행합니다.

```bash
cd ai
python -m pip install -r requirements-dev.txt
python -m pytest -q
python -m uvicorn main:app --reload --host 127.0.0.1 --port 8000
```

## Swagger / OpenAPI 문서

실행 중인 코드와 Pydantic 모델에서 문서를 자동 생성합니다.

| 주소 | 용도 |
|---|---|
| <http://127.0.0.1:8000/docs> | Swagger UI: 요청 필드·응답 확인 및 로컬 호출 |
| <http://127.0.0.1:8000/redoc> | 읽기용 API 문서 |
| <http://127.0.0.1:8000/openapi.json> | 문서 도구·클라이언트 생성에 사용하는 OpenAPI JSON |
| <http://127.0.0.1:8000/health> | 외부 의존성을 호출하지 않는 프로세스 생존 확인 |

`AI_DOCS_ENABLED`의 기본값은 `true`입니다. 문서가 필요한 로컬 개발 환경에서는
기본값을 사용하고, 운영에서는 아래처럼 문서 경로를 비활성화할 수 있습니다.
이때 `/docs`, `/redoc`, `/openapi.json`은 404가 되고 API와 `/health`는 계속 동작합니다.
값 변경은 프로세스를 다시 시작해야 적용됩니다. `true/1/yes/on`만 활성화 값으로
취급하며 나머지는 비활성화됩니다.

```bash
AI_DOCS_ENABLED=false python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

AI는 내부 서버 간 호출용입니다. 문서 비활성화는 인증 수단이 아니므로 운영
AI 포트를 외부에 공개하지 않고 백엔드에서 내부 주소로 접근합니다. Swagger에서
GitHub 수집을 직접 호출할 때 실제 토큰을 예시·스크린샷·export 파일에 남기지 않습니다.

`GET /health` 응답은 `{"status":"ok","service":"gitory-ai","version":"0.0.1"}`입니다.
이는 FastAPI 프로세스 생존만 뜻합니다. GitHub 연결, LLM 설정, 카드 생성 준비 상태를
보장하지 않습니다. A 그룹화·diff 근거·STAR 초안은 LLM 환경변수가 없거나 호출에
실패하면 503 `LLM_UNAVAILABLE`입니다. B 질문 생성은 템플릿, 답변 평가는 규칙 기반이며 LLM 판정기는 연결되지
않았습니다.

서버·DB·GitHub·LLM 없이 문서 JSON을 내보낼 수 있습니다. 저장소 루트에서 실행합니다.

```bash
python ai/scripts/export_openapi.py --output docs/openapi/ai.json
```

`--output`을 생략하면 JSON을 stdout으로 출력합니다. 실행 앱과 같은 `create_app()`을
사용하므로 스키마를 별도로 관리하지 않습니다. 이 export는 `AI_DOCS_ENABLED=false`여도
동작하며, 실행 문서 경로를 활성화하지 않습니다. API가 바뀌면 다시 생성합니다.

내부 API의 성공·실패 응답은 `Envelope`이며 요청 검증 실패는 HTTP **400**과
`INVALID_PAYLOAD`입니다. FastAPI 기본 422 문서는 실제 400 핸들러에 맞게 제거했습니다.
수집의 404(`RESOURCE_NOT_FOUND`)·502(`GITHUB_API_ERROR`)도 공통 오류 봉투로
문서화했습니다. 분석 API의 LLM 실패는 503 `LLM_UNAVAILABLE` 봉투이고, 처리하지 못한
내부 예외도 500 `INTERNAL_ERROR`(재시도 불가) 봉투입니다.

문서 설정과 추가 응답 정의는 FastAPI 공식 문서의
[Metadata and Docs URLs](https://fastapi.tiangolo.com/tutorial/metadata/),
[Conditional OpenAPI](https://fastapi.tiangolo.com/how-to/conditional-openapi/),
[Additional Responses](https://fastapi.tiangolo.com/advanced/additional-responses/)
방식을 따릅니다.

## GitHub 활동 수집(A-1)

`POST /internal/collect`는 Spring이 전달한 저장소·사용자 문맥과
`X-GitHub-Token` 헤더를 사용해 commit·PR·review·review comment·issue를
수집합니다. 이 응답은 A의 후보 그룹화 입력이며, 전체 diff patch와 전체 본문은
반환하지 않습니다. 본문은 최대 1,000자의 `body_excerpt`와 절단 여부만 반환하고,
선택된 후보의 상세 diff 분석은 후속 단계에서 별도로 수행합니다.
토큰은 요청 본문·응답·로그·DB에 남기지 않고 호출 중 메모리에서만 사용합니다.

재수집에서는 작성 시각 기반 `since`를 사용하지 않습니다. Spring이 DB에
저장된 `known_commit_shas`를 보내면, AI는 기본 브랜치의 commit 목록을 다시
확인하되 저장된 SHA의 상세 조회를 생략합니다. 따라서 개인 브랜치에서 과거에
작성됐다가 나중에 기본 브랜치에 합쳐진 commit도 누락되지 않습니다. 응답에는
현재 기본 브랜치의 `head_sha`를 포함하며 Spring이 저장소 상태로 보관합니다.

`POST /internal/collect/candidate-details`는 A가 선택한 `commit_shas`
또는 `github_pr_number`만 다시 조회해 제한된 diff patch를 반환합니다.
개별 patch는 20,000자, 후보 전체는 100,000자까지며, 상한 초과 시
`patch_truncated`와 `partial_reason=CAP_EXCEEDED`로 표시합니다. 바이너리
또는 GitHub이 patch를 제공하지 않는 파일은 `patch: null`입니다.
원본 patch는 A의 요청 처리 중에만 사용하고 DB에 영속 저장하지 않습니다.

A의 분석 로직은 `ports/repository_activity.py`의 `RepositoryActivityPort`에
의존하고, GitHub REST API 구현체는 `services/github_collector.py`에 두었습니다.

일반 테스트는 모의 GitHub 응답을 사용합니다. 실제 GitHub smoke test는 작은
테스트 저장소와 아래 환경변수를 명시한 경우에만 실행됩니다.

```bash
read -s "GITORY_GITHUB_LIVE_TOKEN?GitHub token: "; echo
export GITORY_GITHUB_LIVE_TOKEN
export GITORY_GITHUB_LIVE_OWNER='repository-owner'
export GITORY_GITHUB_LIVE_REPO='repository'
export GITORY_GITHUB_LIVE_BRANCH='develop'
export GITORY_GITHUB_LIVE_ACTOR='your-github-login'
python -m pytest -q -m live
unset GITORY_GITHUB_LIVE_TOKEN
```

실제 토큰 값은 `.env`, 테스트 fixture, 명령 기록 또는 Git에 저장하지 않습니다.

## diff 근거·STAR 초안(A)

사용자가 확정한 후보를 인터뷰 전 STAR 초안으로 만듭니다. Spring 연동 계약은
[A 파트 diff 근거·STAR 초안 연동](../docs/AI_DIFF_STAR_INTEGRATION.md)에 정리했습니다.

1. `POST /internal/analysis/diff-evidence`: 후보 커밋의 diff를 GitHub에서 다시 읽고
   (`X-GitHub-Token`), 비밀값을 가린 뒤 커밋별 요약·기술 포인트로 정리합니다.
   patch 원문은 응답에 싣지 않습니다. (`services/diff_analyzer.py`)
2. `POST /internal/analysis/star`: 1의 `evidence`로 STAR 초안을 만들고, 근거 SHA 실재·
   근거 없는 문장은 비웁니다. 근거에 없는 수치가 든 문장은 비우지 않고 `NEEDS_REVIEW`로 내립니다. 비운 칸은 `missing_fields`로
   B 인터뷰에 넘깁니다. (`services/star_generator.py`)

LLM 설정은 그룹화와 함께 `services/llm_settings.py`가 환경변수로 읽습니다. 연결 정보는
공유하고 모델·추론 강도만 작업별로 바꿉니다. 설정이 없어도 서버는 기동하고 해당 API만
503을 반환합니다.

| 변수 | 기본값 |
|---|---|
| `GITORY_LLM_BASE_URL`, `GITORY_LLM_API_KEY` | 필수 |
| `GITORY_LLM_TIMEOUT_SECONDS` | `120` |
| `GITORY_GROUPING_MODEL`, `GITORY_GROUPING_REASONING_EFFORT` | `gpt-5.6-luna`, `medium` |
| `GITORY_DIFF_MODEL`, `GITORY_DIFF_REASONING_EFFORT` | `gpt-5.6-luna`, `medium` |
| `GITORY_STAR_MODEL`, `GITORY_STAR_REASONING_EFFORT` | `gpt-5.6-luna`, `medium` |
| `GITORY_JOB_POSTING_MODEL`, `GITORY_JOB_POSTING_REASONING_EFFORT` | `gpt-5.6-luna`, `medium` |

프롬프트와 출력 스키마는 `evals/tasks/diff_summary.py`·`star_draft.py` 모델 비교에서
검증한 것과 같은 코드를 공유합니다.

## 기업·직무 공고 RAG

`POST /internal/job-postings/extract`는 Spring이 전달한 공고 상세 본문에서 직무 조건을 뽑습니다.
AI는 원문에 그대로 존재하는 `source_quote`가 있는 담당업무·필수·우대·기술만 반환하고,
Spring은 원문 전체 대신 정규화 조건과 SHA-256 본문 해시를 저장합니다. 같은 해시면 LLM
추출을 생략합니다. 인용이 원문에 없는 조건은 필수(`REQUIRED`)이면 공고 전체를
`EVIDENCE_MISMATCH`(422)로 거절하고, 담당업무·우대·기술이면 그 조건만 빼고 나머지를 돌려줍니다.
`text`에 인용에 없는 낱말이 있으면 거절하지 않고 `text`를 원문 인용으로 바꿔 돌려줍니다(이때 `text`와 `source_quote`가 같습니다).
`text` 길이 상한(1000자)을 넘는 인용은 근거 없는 조건처럼 다루고, 모델이 조건을 냈는데 전부 빠지면 422로 거절합니다.
공고를 어디서 어떻게 가져올지는 Spring이 정하며 AI는 본문만 받습니다.

Spring이 상세 직무 조건과 출처를 저장한 뒤
`POST /internal/matching/postings/index`로 활성 공고를 보내면 AI는 담당업무·
필수조건·우대조건·기술 단위로 임베딩해 ChromaDB 파생 인덱스를 갱신합니다.
마감·비활성 공고는 같은 API로 전달하면 검색 인덱스에서 제거됩니다.

`POST /internal/matching/search`는 Spring이 검증한 확정 STAR 문장만 받습니다.
STAR 원문은 ChromaDB에 저장하지 않고 요청 동안만 임베딩하며, 응답에는 매칭된
공고 조건과 `card_id`·STAR 슬롯을 함께 반환합니다.

`extract`·`postings/index`·`search`의 실패는 모두 `success=false` 오류 봉투로 돌려줍니다.
LLM·임베딩 설정 누락, 키·권한 오류, 임베딩 하루 한도는 503 `retryable=false`, 일시 실패는 503
`retryable=true`, 예상하지 못한 오류는 500 `INTERNAL_ERROR`입니다. 임베딩 일시 실패의 503(`retryable=true`)은 OpenAI 호환
임베딩을 쓸 때 나가고, 기본 로컬 임베딩(`chroma-default`, 첫 호출 때 모델을 내려받음)의 실패는
500 `INTERNAL_ERROR`입니다.

필요한 환경변수는 다음과 같습니다.

```bash
export GITORY_EMBEDDING_PROVIDER='chroma-default'       # 운영 초기값
export GITORY_LOCAL_EMBEDDING_CACHE='.chroma/models/all-MiniLM-L6-v2'
export GITORY_CHROMA_PATH='.chroma'                     # 기본값
export GITORY_MATCH_MAX_DISTANCE='0.4'                 # 평가 후 확정
```

Elice의 Luna는 Text Generation 모델이라 임베딩 API를 제공하지 않는다. 따라서
공고 조건 추출은 Luna를 그대로 사용하고 벡터화는 로컬 ONNX
`all-MiniLM-L6-v2`가 담당한다. 최초 실행 때 약 80MB 모델을 내려받는다.
나중에 OpenAI 호환 임베딩 모델을 사용하면 아래 설정으로 구현을 바꾸지 않고
교체할 수 있다.

```bash
export GITORY_EMBEDDING_PROVIDER='openai'
export GITORY_EMBEDDING_API_KEY='임베딩 API 키'
export GITORY_EMBEDDING_BASE_URL='OpenAI 호환 API URL'
export GITORY_EMBEDDING_MODEL='text-embedding-3-small'
```

30개 합성 공고 검색 평가는 실제 기업 추천이 아니라 검색 품질 회귀 확인용입니다.

```bash
cd ai
python -m scripts.evaluate_job_matching \
  --output evals/results/job_matching.json
```

API 키 없이 실제 벡터·거리 분포를 먼저 확인하려면 Chroma 기본 ONNX 임베딩을
사용할 수 있습니다. 이 결과는 운영 임베딩 모델 선정 결과가 아니므로, 운영 모델이
정해지면 동일 fixture를 `--provider openai`로 다시 실행해 거리 상한을 확정합니다.

```bash
python -m scripts.evaluate_job_matching \
  --provider chroma-default \
  --maximum-distance 0.4 \
  --output evals/results/job_matching-chroma-default.json
```
