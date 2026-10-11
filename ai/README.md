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
보장하지 않습니다. 현재 A 그룹화·STAR 파이프라인은 미구현으로 유효한 요청도 500으로
실패합니다. B 질문 생성은 템플릿, 답변 평가는 규칙 기반이며 LLM 판정기는 연결되지
않았습니다. 등록되지 않은 matching/RAG stub은 API 문서에 나타나지 않습니다.

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
문서화했습니다. 미구현 A 경로의 500은 현재 기본 서버 오류(text/plain)이며,
Swagger의 200 응답 스키마는 앞으로 구현할 계약입니다.

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

## 기업·직무 공고 RAG

`POST /internal/job-postings/discover`는 사람인 공식 API에서 공고 ID·제목·
직무코드·키워드·등록/수정/마감 시각을 수집합니다. 사람인 검색 API가 공고
본문 전체를 제공하지 않으므로 이 응답을 필수·우대 조건으로 지어내거나 곧바로
벡터화하지 않습니다. `SARAMIN_ACCESS_KEY`는 AI 서버 환경변수로만 주입합니다.

상세 조건은 사람인 검색 키워드에서 추론하지 않습니다. V1은 사용 허용을 확인한
공개 기업 채용 페이지 본문을 Spring이 `POST /internal/job-postings/extract`로
전달합니다. AI는 원문에 그대로 존재하는 `source_quote`가 있는 담당업무·필수·
우대·기술만 반환하고, Spring은 원문 전체 대신 정규화 조건과 SHA-256 본문 해시를
저장합니다. 같은 해시면 LLM 추출을 생략합니다. 자동 HTML 크롤링은 V1 범위가
아니며 출처별 이용 조건을 확정한 뒤 별도 어댑터로 추가합니다.

Spring이 상세 직무 조건과 출처를 저장한 뒤
`POST /internal/matching/postings/index`로 활성 공고를 보내면 AI는 담당업무·
필수조건·우대조건·기술 단위로 임베딩해 ChromaDB 파생 인덱스를 갱신합니다.
마감·비활성 공고는 같은 API로 전달하면 검색 인덱스에서 제거됩니다.

`POST /internal/matching/search`는 Spring이 검증한 확정 STAR 문장만 받습니다.
STAR 원문은 ChromaDB에 저장하지 않고 요청 동안만 임베딩하며, 응답에는 매칭된
공고 조건과 `card_id`·STAR 슬롯을 함께 반환합니다.

필요한 환경변수는 다음과 같습니다.

```bash
export SARAMIN_ACCESS_KEY='사람인 Open API 키'
export GITORY_EMBEDDING_PROVIDER='chroma-default'       # 운영 초기값
export GITORY_LOCAL_EMBEDDING_CACHE='.chroma/models/all-MiniLM-L6-v2'
export GITORY_CHROMA_PATH='.chroma'                     # 기본값
export GITORY_MATCH_MAX_DISTANCE='0.4'                 # 평가 후 확정
export GITORY_MATCH_GRADE_A_MIN='4'
export GITORY_MATCH_GRADE_B_MIN='3'
export GITORY_MATCH_GRADE_C_MIN='1'
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
