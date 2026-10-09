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
문서화했습니다. 분석 API의 LLM 실패는 503 `LLM_UNAVAILABLE` 봉투이고, 처리하지 못한
내부 예외만 기본 서버 오류(500, text/plain)입니다.

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

## diff 근거·STAR 초안(A)

사용자가 확정한 후보를 인터뷰 전 STAR 초안으로 만듭니다. Spring 연동 계약은
[A 파트 diff 근거·STAR 초안 연동](../docs/AI_DIFF_STAR_INTEGRATION.md)에 정리했습니다.

1. `POST /internal/analysis/diff-evidence`: 후보 커밋의 diff를 GitHub에서 다시 읽고
   (`X-GitHub-Token`), 비밀값을 가린 뒤 커밋별 요약·기술 포인트로 정리합니다.
   patch 원문은 응답에 싣지 않습니다. (`services/diff_analyzer.py`)
2. `POST /internal/analysis/star`: 1의 `evidence`로 STAR 초안을 만들고, 근거 SHA 실재·
   지어낸 수치를 코드로 검증해 근거 없는 문장은 비웁니다. 비운 칸은 `missing_fields`로
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

프롬프트와 출력 스키마는 `evals/tasks/diff_summary.py`·`star_draft.py` 모델 비교에서
검증한 것과 같은 코드를 공유합니다.
