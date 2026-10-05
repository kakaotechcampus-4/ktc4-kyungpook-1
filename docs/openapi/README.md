# OpenAPI 스냅샷

`backend.json`은 실행 중인 Spring `/api/docs`, `ai.json`은 FastAPI의 `create_app().openapi()`에서 생성한다. 문서 작업과 API 비교에 사용할 수 있으며, 최신 계약은 현재 코드에서 다시 생성한 파일이다.

동봉한 스냅샷은 2026-10-04 생성했으며 BE 4개 경로·AI 7개 경로(health 포함)를 담는다.

```bash
# 저장소 루트. BE 실행 및 AI Python 의존성 설치 후:
PYTHON_BIN=.venv/bin/python bash scripts/export-api-docs.sh
```

서버 주소·Swagger 사용법·인증·미구현 범위는 [BE–AI 연결 가이드](../BE_AI_INTEGRATION.md)를 참고한다. AI A의 200 스키마는 구현할 계약이며 현재 유효 요청도 500으로 실패한다. BE 분석 API의 200은 Job 접수 성공이며 워커 실행 완료가 아니다.

JSON을 수작업으로 수정하지 않는다. 컨트롤러·Pydantic 모델을 변경하고 다시 내보낸다. 실제 토큰·쿠키·비밀값을 예시에 추가하지 않는다.
