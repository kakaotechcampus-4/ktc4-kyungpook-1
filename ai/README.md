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
uvicorn main:app --reload
```

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
