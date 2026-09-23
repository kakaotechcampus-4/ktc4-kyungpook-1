# AI

Gitory AI 서버의 독립 실행 루트입니다. A(LLM 분석)와 B(Agent/RAG) 코드를
이 디렉터리에서 함께 관리합니다.

## 구조

```text
ai/
├── api/
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

## GitHub 수집 보안 경계

Spring은 저장소 접근 권한을 확인하고 암호화 토큰을 복호화한 뒤
`POST /internal/github/collect`의 `X-GitHub-Token` 헤더로만 전달합니다.
AI 서버는 요청마다 GitHub 클라이언트를 열고 닫으며 토큰을 DB, 파일, 전역 상태,
응답 또는 로그에 저장하지 않습니다.

- Spring ↔ AI 구간은 운영 환경에서 반드시 TLS 또는 격리된 내부 네트워크를 사용합니다.
- 프록시/APM 로그에서 `X-GitHub-Token` 헤더를 수집하지 않도록 마스킹합니다.
- 요청 본문에 토큰을 넣지 않습니다.
- GitHub OAuth 권한은 제품에 필요한 최소 읽기 권한만 사용합니다.

### GitHub 수집 에러 코드

| GitHub 응답 | AI HTTP | `error.code` | `retryable` | Spring 처리 |
|---|---|---|---|---|
| 401 | 502 | `GITHUB_AUTH_FAILED` | false | 토큰 만료·폐기. GitHub 재연동 유도 |
| 403 (rate limit 아님) | 502 | `GITHUB_PERMISSION_DENIED` | false | 권한·조직 SSO 승인 부족 안내 |
| 404 | 502 | `GITHUB_REPO_NOT_FOUND` | false | 저장소 없음 또는 비공개 저장소 접근 불가 |
| 403/429 rate limit (`X-RateLimit-Remaining: 0` 또는 `Retry-After`) | 503 | `GITHUB_API_ERROR` | true | 대기 후 재시도 |
| 그 외 오류·네트워크 실패 | 502 | `GITHUB_API_ERROR` | true | 재시도 |

수집 도중 rate limit에 걸리면 에러 대신 `partial=true`, `GITHUB_RATE_LIMITED`로 부분 결과를 반환합니다.

Python 문자열은 불변이고 메모리 해제 시점이 GC에 의해 결정되므로 토큰 메모리 영역을
즉시 0으로 덮어쓰는 것은 보장하지 않습니다. 대신 요청 스코프 밖으로 참조가 탈출하지
않도록 제한하고 요청 종료 시 HTTP 클라이언트를 닫습니다.
