# OpenAPI 스냅샷

`backend.json`은 Spring `/api/docs`, `ai.json`은 FastAPI의 `create_app().openapi()`에서 만든 문서다. 운영에서는 두 문서를 열지 않으므로, 서버를 띄우지 않고 API를 볼 때 이 파일을 쓴다.

스냅샷이 코드와 다르면 BE `OpenApiIntegrationTest`와 AI `test_committed_snapshot_matches_running_schema`가 실패한다. API를 바꾼 PR은 스냅샷을 다시 만들어 같이 커밋한다.

```bash
# 둘 다 다시 만들기 (저장소 루트, Docker 실행 중)
PYTHON_BIN=.venv/bin/python bash scripts/export-api-docs.sh

# BE 만 (backend 폴더)
UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiIntegrationTest'

# AI 만 (ai 폴더)
UPDATE_OPENAPI=1 python -m pytest tests/test_openapi_health.py
```

PowerShell 에서는 `$env:UPDATE_OPENAPI=1; <명령>; Remove-Item Env:UPDATE_OPENAPI` 처럼 쓴다.

서버 주소·Swagger 사용법·인증은 [BE–AI 연결 가이드](../BE_AI_INTEGRATION.md)를 참고한다. BE 분석 API의 200은 Job 접수 성공이며 워커 실행 완료가 아니다.

JSON을 수작업으로 수정하지 않는다. 컨트롤러·Pydantic 모델을 바꾸고 다시 만든다. 실제 토큰·쿠키·비밀값을 예시에 추가하지 않는다.
